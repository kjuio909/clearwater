package org.jsoup.internal;

import org.jsoup.Progress;
import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

import static org.jsoup.internal.SharedConstants.DefaultBufferSize;

/**
 * A jsoup internal class (so don't use it as there is no contract API) that enables controls on a buffered input stream,
 * namely a maximum read size, and the ability to Thread.interrupt() the read.
 */
// reimplemented from ConstrainableInputStream for JDK21 - extending BufferedInputStream will pin threads during read
public class ControllableInputStream extends FilterInputStream {
    private final SimpleBufferedInput buff; // super.in, but typed as SimpleBufferedInput
    private int maxSize;                    // logical cap exposed to callers (0 == unlimited)
    private long startTime;                 // start time for timeout checks, nanos
    private long timeout = 0;               // optional max time of request
    private int remaining;                  // how many bytes may still be returned to caller under the current cap
    private int markPos;                    // logical readPos snapshot for InputStream.mark/reset (not a buffer cursor)
    private boolean interrupted;            // true if Thread.interrupted() was detected, used to latch interrupted state
    private boolean truncated;              // true if there is content beyond the body cap
    private boolean allowClose = true;      // for cases where we want to re-read the input, can ignore .close() from the parser
    private final byte[] singleByte = new byte[1]; // avoids allocation when routing single-byte reads through controls

    // if we are tracking progress, will have the expected content length and progress callback state
    private @Nullable ProgressState<?> progress;
    private int contentLength = -1;         // declared content length for progress; -1 == unknown, and is reported as-is
    private int readPos = 0;                // logical amount read; can move backwards on reset()
    private int highWater = 0;              // furthest logical position ever read; progress never reports below this
    private int lastEmitted = -1;           // last processed value emitted, to suppress duplicate re-read events
    private boolean emittedInitial = false; // the initial (0, total, 0%) event has been emitted
    private boolean completed = false;      // end of entity has been reached (terminal handling done once)

    private ControllableInputStream(SimpleBufferedInput in, int maxSize) {
        super(in);
        Validate.isTrue(maxSize >= 0);
        buff = in;
        this.maxSize = maxSize;
        remaining = maxSize;
        markPos = -1;
        startTime = System.nanoTime();
    }

    /**
     * If this InputStream is not already a ControllableInputStream, let it be one.
     * @param in the input stream to (maybe) wrap. A {@code null} input will create an empty wrapped stream.
     * @param maxSize the maximum size to allow to be read. 0 == infinite.
     * @return a controllable input stream
     */
    public static ControllableInputStream wrap(@Nullable InputStream in, int maxSize) {
        // bufferSize currently unused; consider implementing as a min size in the SoftPool recycler
        if (in instanceof ControllableInputStream)
            return (ControllableInputStream) in;
        else
            return new ControllableInputStream(new SimpleBufferedInput(in), maxSize);
    }

    /**
     * If this InputStream is not already a ControllableInputStream, let it be one.
     * @param in the input stream to (maybe) wrap
     * @param bufferSize the buffer size to use when reading
     * @param maxSize the maximum size to allow to be read. 0 == infinite.
     * @return a controllable input stream
     */
    public static ControllableInputStream wrap(InputStream in, int bufferSize, int maxSize) {
        // todo - bufferSize currently unused; consider implementing as a min size in the SoftPool recycler; or just deprecate if always DefaultBufferSize
        return wrap(in, maxSize);
    }

    @Override
    public int read() throws IOException {
        int read = read(singleByte, 0, 1); // route through the controlled read, so caps and progress account for single-byte reads
        return read == -1 ? -1 : singleByte[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        emitInitialProgress();

        boolean capped = maxSize != 0;
        if (interrupted)
            return -1;
        if (Thread.currentThread().isInterrupted()) {
            // interrupted latches, because parse() may call twice
            interrupted = true;
            return -1;
        }
        if (capped && remaining <= 0) {
            if (checkTruncated()) return -1; // more content exists beyond the cap: this is a truncation, not an end of entity
            complete(); // entity ended exactly on the cap
            return -1;
        }

        if (capped && len > remaining)
            len = remaining; // don't read more than desired, even if available
        if (capped) buff.capRemaining(remaining);
        else buff.uncap();

        while (true) { // loop trying to read until we get some data or hit the overall timeout, if we have one
            if (expired())
                throw new SocketTimeoutException("Read timeout");

            try {
                final int read = super.read(b, off, len);
                if (read == -1) { // the underlying entity genuinely ended
                    complete();
                } else {
                    if (capped && read > 0) {
                        remaining -= read; // track bytes returned to the caller
                    }
                    // todo: use long progress values in the public API; saturate until that is available
                    readPos = read > Integer.MAX_VALUE - readPos ? Integer.MAX_VALUE : readPos + read;
                    emitProgress();
                }
                return read;
            } catch (SocketTimeoutException e) {
                if (expired() || timeout == 0)
                    throw e;
            }
        }
    }

    @Override
    public long skip(long requested) throws IOException {
        // implemented here so our cap accounting and progress stay aligned with actual reads
        if (requested <= 0) return 0;

        byte[] skipBuffer = SimpleBufferedInput.BufferPool.borrow();
        long skipped = 0;
        try {
            while (skipped < requested) {
                int read = read(skipBuffer, 0, (int) Math.min(requested - skipped, skipBuffer.length));
                if (read == -1) break;
                skipped += read;
            }
            return skipped;
        } finally {
            SimpleBufferedInput.BufferPool.release(skipBuffer);
        }
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    /**
     * Reads this inputstream to a ByteBuffer. The supplied max may be less than the inputstream's max, to support
     * reading just the first bytes.
     */
    public static ByteBuffer readToByteBuffer(InputStream in, int max) throws IOException {
        Validate.isTrue(max >= 0, "maxSize must be 0 (unlimited) or larger");
        Validate.notNull(in);
        final boolean capped = max > 0;
        final byte[] readBuf = SimpleBufferedInput.BufferPool.borrow(); // Share the same byte[] pool as SBI
        final int outSize = capped ? Math.min(max, DefaultBufferSize) : DefaultBufferSize;
        ByteBuffer outBuf = ByteBuffer.allocate(outSize);

        try {
            int remaining = max;
            int read;
            while ((read = in.read(readBuf, 0, capped ? Math.min(remaining, DefaultBufferSize) : DefaultBufferSize)) != -1) {
                if (outBuf.remaining() < read) { // needs to grow
                    int newCapacity = (int) Math.max(outBuf.capacity() * 1.5, outBuf.capacity() + read);
                    ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
                    outBuf.flip();
                    newBuffer.put(outBuf);
                    outBuf = newBuffer;
                }
                outBuf.put(readBuf, 0, read);
                if (capped) {
                    remaining -= read;
                    if (remaining <= 0) break;
                }
            }
            outBuf.flip(); // Prepare the buffer for reading
            return outBuf;
        } finally {
            SimpleBufferedInput.BufferPool.release(readBuf);
        }
    }

    @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod") // not synchronized in later JDKs
    @Override public void reset() throws IOException {
        if (markPos < 0) throw new IOException("Resetting to invalid mark");
        buff.rewindToMark();
        buff.clearMark();
        truncated = false;
        if (maxSize != 0) {
            remaining = maxSize - markPos;
            buff.capRemaining(remaining);
        } else {
            remaining = 0;
            buff.uncap();
        }
        readPos = markPos; // logical position rewinds; highWater keeps delivered progress monotonic across the re-read
        markPos = -1;
    }

    @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod")
    @Override public void mark(int readlimit) {
        markPos = readPos;
        buff.setMark();
    }

    /**
     Check if the underlying InputStream has been read fully. There may still content in buffers to be consumed, and
     read methods may return -1 if hit the read limit.
     @return true if the underlying inputstream has been read fully.
     */
    public boolean baseReadFully() {
        return buff.baseReadFully();
    }

    public void resetFullyRead() {
        buff.resetFullyRead();
    }

    /**
     Get the max size of this stream (how far at most will be read from the underlying stream)
     * @return the max size
     */
    public int max() {
        return maxSize;
    }

    public void max(int newMax) {
        remaining += newMax - maxSize; // update remaining to reflect the difference in the new maxsize
        if (remaining < 0) remaining = 0;
        if (newMax == 0 || newMax > maxSize) truncated = false;
        maxSize = newMax;
        if (newMax == 0) buff.uncap();
        else buff.capRemaining(remaining);
    }

    /**
     Probes whether content remains beyond the configured cap, without pulling that content into the logical stream.
     @return true if the entity was truncated by the cap; false if the cap was reached at a genuine end of entity
     */
    public boolean checkTruncated() throws IOException {
        while (!truncated && maxSize != 0 && remaining <= 0) {
            if (expired()) throw new SocketTimeoutException("Read timeout");
            try {
                truncated = buff.hasMore();
                break;
            } catch (SocketTimeoutException e) {
                if (expired() || timeout == 0) throw e;
            }
        }
        return truncated;
    }

    /**
     Returns whether content was found beyond the configured cap.
     */
    public boolean isTruncated() {
        return truncated;
    }

    public void allowClose(boolean allowClose) {
        this.allowClose = allowClose;
    }

    @Override public void close() throws IOException {
        if (allowClose) super.close();
    }

    public ControllableInputStream timeout(long startTimeNanos, long timeoutMillis) {
        this.startTime = startTimeNanos;
        this.timeout = timeoutMillis * 1000000;
        return this;
    }

    /**
     Emits the single initial progress event {@code (0, total, 0%)} on the first controlled read. A declared length
     of zero is an already-complete entity, so its one and only event is the terminal completion.
     */
    private void emitInitialProgress() {
        if (emittedInitial || progress == null) return;
        if (contentLength == 0) { // empty entity: nothing can ever be read, so complete immediately and exactly once
            complete();
            return;
        }
        emittedInitial = true;
        highWater = Math.max(highWater, readPos);
        int processed = reportPosition();
        lastEmitted = processed;
        float percent = percentFor(processed);
        deliver(processed, contentLength, percent);
    }

    /**
     Emits an intermediate progress event, unless the logical position is being re-read after a reset, in which case
     the already-reported high-water position is held so that progress never moves backwards or repeats. When the
     declared length is exceeded, {@code processed} is held at the declared value rather than reported past it.
     */
    private void emitProgress() {
        ProgressState<?> progress = this.progress;
        if (progress == null) return;
        highWater = Math.max(highWater, readPos);
        int processed = reportPosition();
        if (processed == lastEmitted) return; // re-reading buffered content after a reset, or held at the declared cap
        lastEmitted = processed;
        deliver(processed, contentLength, percentFor(processed));
    }

    /** The position to report: the delivered high-water mark, never exceeding a declared length. */
    private int reportPosition() {
        return contentLength > 0 ? Math.min(highWater, contentLength) : highWater;
    }

    /**
     Percent for an intermediate position. {@code 100%} is reserved for the single terminal event confirmed at
     end-of-entity, so an intermediate position that meets the declared total reports just below it, and an unknown
     length always reports {@code 0}.
     */
    private float percentFor(int processed) {
        if (contentLength <= 0) return 0f;
        return Math.min(Math.nextDown(100f), processed * 100f / contentLength);
    }

    /**
     * Delegates a progress event to the registered callback, isolating any {@link RuntimeException} the callback
     * throws: the callback is a side-channel observer, so its failure skips only that single notification. The
     * callback stays registered, the network read/parse/close continue, and the next progress event is still
     * delivered to the same callback.
     */
    private void deliver(int processed, int total, float percent) {
        ProgressState<?> progress = this.progress;
        if (progress == null) return;
        progress.emit(processed, total, percent);
    }

    /**
     Performs the exactly-once terminal handling when the entity has been confirmed read to its end. Reaching a
     configured cap is not an end while further content exists, and an I/O failure never reaches this method, so a
     failed read never reports completion. The terminal event is only a {@code 100%} completion when the delivered
     entity matches its declared length (or no length was declared, in which case {@code total} stays {@code -1});
     a declared-length mismatch reports the honest partial delivery below {@code 100%} and is never a success.
     */
    private void complete() {
        if (progress == null || completed) return;
        completed = true;
        emittedInitial = true;
        highWater = Math.max(highWater, readPos);
        int delivered = highWater;
        if (contentLength > 0 && delivered != contentLength) {
            // declared length does not match the entity actually delivered: keep the exception/read-data semantics of
            // the stream itself, but never report 100% nor treat the request as successfully complete. Report the
            // honest partial delivery (held below the declared value), but only when it advances beyond the last
            // notification, so a mismatch never produces a redundant non-progress event.
            int processed = Math.min(delivered, contentLength);
            if (processed != lastEmitted) {
                lastEmitted = processed;
                deliver(processed, contentLength, percentFor(processed));
            }
        } else {
            // genuine end of entity: unknown length (total stays -1), a declared zero, or an exact declared length
            lastEmitted = delivered;
            deliver(delivered, contentLength, 100f);
        }
        this.progress = null; // terminal handling happens once; a later buffered re-drain must not report again
    }

    public <ProgressContext> ControllableInputStream onProgress(int contentLength,
        Progress<ProgressContext> callback, ProgressContext context) {
        Validate.notNull(callback);
        Validate.notNull(context);
        this.contentLength = contentLength;
        this.progress = new ProgressState<>(callback, context);
        // each registration observes independently from the current logical position, without leaking the
        // completion or high-water state of a previously registered callback
        emittedInitial = false;
        completed = false;
        lastEmitted = -1;
        highWater = readPos;
        return this;
    }

    private boolean expired() {
        if (timeout == 0)
            return false;

        final long now = System.nanoTime();
        final long dur = now - startTime;
        return (dur > timeout);
    }

    public BufferedInputStream inputStream() {
        // called via HttpConnection.Response.bodyStream(); that public stream is intentionally not subject to the
        // request's maxBodySize cap (parse/readFully are), so wrap the underlying buffer directly rather than this
        return new BufferedInputStream(buff);
    }

    private static class ProgressState<ProgressContext> {
        private final Progress<ProgressContext> callback;
        private final ProgressContext context;

        ProgressState(Progress<ProgressContext> callback, ProgressContext context) {
            this.callback = callback;
            this.context = context;
        }

        /**
         Invokes the callback, isolating a {@link RuntimeException} it throws to this single notification. Progress
         is a side-channel observation that must never change the fetch, so the exception is swallowed and is not
         propagated to the reader. The callback is not detached: the next progress event is delivered to it normally,
         and a throw on one event neither fabricates nor suppresses the completion event.
         */
        void emit(int processed, int total, float percent) {
            try {
                callback.onProgress(processed, total, percent, context);
            } catch (RuntimeException e) {
                // skip only this notification; the read, close, and later notifications continue
            }
        }
    }
}
