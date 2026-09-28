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
    private int contentLength = -1;         // declared content length for progress; -1 == unknown
    private boolean successful = true;     // whether the response was a successful status; failures never report completion
    private int readPos = 0;                // logical amount read; can move backwards on reset()
    private int highWater = 0;              // furthest logical position ever read; progress never reports below this
    private int lastEmitted = -1;           // last processed value emitted, to suppress duplicate re-read events
    private boolean emittedInitial = false; // the initial (0, total, 0%) event has been emitted
    private boolean completed = false;      // the terminal completion event has been emitted

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
        int processed = Math.max(readPos, highWater);
        highWater = processed;
        lastEmitted = processed;
        float percent = contentLength > 0 ? Math.min(100f, processed * 100f / contentLength) : 0f;
        deliver(processed, contentLength, percent);
    }

    /**
     Emits an intermediate progress event, unless the logical position is being re-read after a reset, in which case
     the already-reported high-water position is held so that progress never moves backwards or repeats.
     */
    private void emitProgress() {
        ProgressState<?> progress = this.progress;
        if (progress == null) return;
        int processed = Math.max(readPos, highWater);
        highWater = processed;
        if (processed == lastEmitted) return; // re-reading buffered content after a reset: no new progress
        lastEmitted = processed;
        // calculate percent complete against the declared length; unknown length stays at 0
        float percent = contentLength > 0 ? processed * 100f / contentLength : 0f;
        // 100% is reserved for the single terminal event confirmed at end-of-entity; an intermediate read that
        // already meets (or exceeds, e.g. with a compressed declared length) the total reports just below it
        if (percent >= 100f) percent = Math.nextDown(100f);
        deliver(processed, contentLength, percent);
    }

    /**
     * Delegates a progress event to the registered callback, isolating any {@link RuntimeException} the callback
     * throws: the callback is a side-channel observer, so its failure must neither abort the read nor be forged into
     * a completion. After such a failure the callback is detached and receives no further events for this request,
     * while the network read, parsing, and close all continue as if no callback was registered.
     */
    private void deliver(int processed, int total, float percent) {
        ProgressState<?> progress = this.progress;
        if (progress == null) return;
        if (!progress.emit(processed, total, percent))
            this.progress = null;
    }

    /**
     Emits the exactly-once terminal completion event when the entity has been confirmed read to its end. Reaching a
     configured cap is not completion while further content exists; an I/O failure does not call this, so a failed
     read never reports completion. An unsuccessful response status may report the bytes actually delivered, but never
     a 100% completion.
     */
    private void complete() {
        highWater = Math.max(highWater, readPos);
        if (progress == null || completed) return;
        completed = true;
        emittedInitial = true;
        int processed = highWater;
        if (successful) {
            // once read to the end, an unknown length is resolved to the delivered length; a declared length is held
            int total = contentLength > 0 ? contentLength : processed;
            deliver(processed, total, 100f);
        } else {
            // unsuccessful status: report the actual partial delivery, but hold below completion and keep total as
            // declared (-1 when unknown) so that the caller never mistakes it for a successful completion
            float percent = contentLength > 0
                ? Math.min(Math.nextDown(100f), processed * 100f / contentLength)
                : 0f;
            deliver(processed, contentLength, percent);
        }
        this.progress = null; // detach: no later buffered hit may report another completion
    }

    public <ProgressContext> ControllableInputStream onProgress(int contentLength, Progress<ProgressContext> callback, ProgressContext context) {
        return onProgress(contentLength, true, callback, context);
    }

    public <ProgressContext> ControllableInputStream onProgress(int contentLength, boolean successful,
        Progress<ProgressContext> callback, ProgressContext context) {
        Validate.notNull(callback);
        Validate.notNull(context);
        this.contentLength = contentLength;
        this.successful = successful;
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
         * Invokes the callback and reports whether it returned normally. A {@link RuntimeException} thrown by the
         * callback is swallowed here (progress is a side-channel observation that must never change the fetch
         * result); {@code false} tells the caller to detach this failed callback for the rest of the request.
         @return true if the callback accepted the event; false if it threw
         */
        boolean emit(int processed, int total, float percent) {
            try {
                callback.onProgress(processed, total, percent, context);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
