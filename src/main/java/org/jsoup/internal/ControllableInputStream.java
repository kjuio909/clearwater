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
import java.util.function.Supplier;

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

    // if we are tracking progress, supplies the currently bound handler (re-read live on every event); a null result
    // means no handler is registered (cancelled or replaced), so that switching or removing a handler takes effect
    // immediately without re-registering the stream.
    private @Nullable Supplier<? extends @Nullable Handle<?>> progressSource;
    private @Nullable Object lastCallback;       // callback last notified, to detect a live switch to a new handler
    private int contentLength = -1;         // declared content length for progress; -1 == unknown
    private boolean successful = true;     // whether the response was a successful status; failures never report completion
    private int readPos = 0;                // logical amount read; can move backwards on reset()
    private int highWater = 0;              // furthest logical position ever read; progress never reports below this
    private int lastEmitted = -1;           // last processed value emitted, to suppress duplicate re-read events
    private boolean emittedInitial = false; // the initial (0, total, 0%) event has been emitted
    private boolean completed = false;      // the terminal completion event has been emitted
    private boolean lengthMismatch = false; // delivered bytes disagreed with the declared length; never complete
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
     * Opens progress observation on the first controlled read. A declared length of zero is an already-complete
     * entity, so its one and only event is the terminal completion; a positive declared length gets an initial
     * {@code (0, total, 0%)} event. An unknown length has no determinable start, so its initial event is deferred
     * until bytes actually arrive (see {@link #emitProgress()}), which lets an unknown-length empty entity produce
     * only its single terminal completion.
     */
    private void emitInitialProgress() {
        Handle<?> handle = activeHandle();
        if (emittedInitial || handle == null) return;
        emittedInitial = true;
        if (contentLength == 0) { // a declared-empty entity: nothing can ever be read, so complete exactly once
            complete();
        } else if (contentLength > 0) {
            // a declared length starts with a determinable (0, total, 0%) event
            int processed = reportedProcessed();
            lastEmitted = processed;
            float percent = Math.min(100f, processed * 100f / contentLength);
            deliver(handle, processed, contentLength, percent);
        }
        // an unknown length has no determinable start: hold the initial event until bytes actually arrive (handled in
        // emitProgress), so an unknown-length empty entity yields only its single terminal completion
    }

    /**
     Emits an intermediate progress event, unless the logical position is being re-read after a reset, in which case
     the already-reported high-water position is held so that progress never moves backwards or repeats.
     */
    private void emitProgress() {
        Handle<?> handle = activeHandle();
        if (handle == null) return;
        int processed = reportedProcessed();
        // an unknown length delays its initial event until bytes actually arrive (so an empty entity has no leading
        // 0% event); emit that single (0, -1, 0) before the first data event
        if (contentLength < 0 && lastEmitted < 0) {
            deliver(handle, 0, contentLength, 0f);
            lastEmitted = 0;
        }
        if (processed == lastEmitted) return; // re-reading buffered content after a reset: no new progress
        lastEmitted = processed;
        // calculate percent complete against the declared length; for an unknown length both percent stay at 0 and
        // total stays -1, right through to the terminal event
        float percent;
        if (contentLength > 0) {
            percent = processed * 100f / contentLength;
            // 100% is reserved for the single terminal event confirmed at end-of-entity; an intermediate read that
            // already meets the declared total (without an end-of-entity confirmation yet) reports just below it
            if (percent >= 100f) percent = Math.nextDown(100f);
        } else {
            percent = 0f;
        }
        deliver(handle, processed, contentLength, percent);
    }

    /**
     * Resolves the handler currently bound to this stream, re-reading the live source on every event. If a different
     * callback instance is now bound, observation state restarts from the current logical position, so the new handler
     * neither inherits the previous handler's high-water/completion nor receives any of its earlier events; a null
     * source result means the handler was cancelled and no event is delivered.
     */
    private @Nullable Handle<?> activeHandle() {
        Supplier<? extends @Nullable Handle<?>> source = progressSource;
        if (source == null) return null;
        Handle<?> handle = source.get();
        if (handle == null) return null;
        if (handle.callback != lastCallback) {
            Object previous = lastCallback;
            lastCallback = handle.callback;
            restartObservation(false); // entity-level facts (length mismatch) carry over; observer counters do not
            if (previous != null) {
                // a live switch between two handlers: the event that discovered the newcomer is its first event, so
                // anchor it at the current position and suppress a separate (0,...) initial. It then observes from
                // where it was bound (no stale 0, no duplicate); the first binding / an explicit re-registration
                // leaves emittedInitial false and lastEmitted -1, so the normal initial event still fires.
                emittedInitial = true;
                lastEmitted = reportedProcessed();
            }
        }
        return handle;
    }

    /**
     * Reports the processed byte count for an event: the furthest logical position actually delivered (held across a
     * mark/reset re-read so progress never walks backwards), clamped to a declared length so {@code processed} can
     * never exceed it. Delivering more bytes than were declared latches {@link #lengthMismatch} so no later event can
     * signal completion.
     */
    private int reportedProcessed() {
        if (readPos > highWater) highWater = readPos;
        int processed = highWater;
        if (contentLength > 0 && processed > contentLength) {
            lengthMismatch = true;
            processed = contentLength;
        }
        return processed;
    }

    /**
     * Delegates a single progress event to the bound callback, isolating any {@link RuntimeException} it throws. The
     * callback is a side-channel observer: its exception is swallowed for this notification only and neither aborts
     * the network read, resource close, or parsing, nor suppresses any later notification (including completion), nor
     * leaks into another callback or the next request.
     */
    @SuppressWarnings("rawtypes")
    private static void deliver(Handle handle, int processed, int total, float percent) {
        try {
            //noinspection unchecked
            handle.callback.onProgress(processed, total, percent, handle.context);
        } catch (RuntimeException e) {
            // this notification only; reads, close, and subsequent notifications continue unaffected
        }
    }

    /**
     * Emits the terminal event once the entity has been confirmed read to its end. A successful {@code 100%}
     * completion is emitted exactly once, only when the status is successful and the delivered entity matches the
     * declared length; {@code total} is then the declared length, or stays {@code -1} for an unknown length (the
     * actual delivered byte count is carried in {@code processed}). Reaching the configured cap while more content
     * exists never gets here (that is a truncation, handled by the caller); an unsuccessful status or a declared
     * length that did not match the actual entity reports only the partial delivery below {@code 100%}; an I/O
     * failure never calls this, so a failed read never reports completion or looks successful.
     */
    private void complete() {
        Handle<?> handle = activeHandle();
        if (handle == null || completed) return;
        completed = true;
        emittedInitial = true;
        if (readPos > highWater) highWater = readPos;
        int actual = highWater;
        boolean mismatch = contentLength > 0 && (lengthMismatch || actual != contentLength);

        if (successful && !mismatch) {
            int processed = contentLength > 0 ? contentLength : actual;
            // an unknown length stays -1 even at the terminal event; processed carries the delivered byte count
            deliver(handle, processed, contentLength, 100f);
        } else {
            // unsuccessful status, or the entity did not match its declared length: report the bytes actually
            // delivered, but hold below completion and keep the declared total (-1 when unknown) so the caller never
            // mistakes it for a successful completion
            int processed = contentLength > 0 ? Math.min(actual, contentLength) : actual;
            float percent = contentLength > 0
                ? Math.min(Math.nextDown(100f), processed * 100f / contentLength)
                : 0f;
            deliver(handle, processed, contentLength, percent);
        }
    }

    public <ProgressContext> ControllableInputStream onProgress(int contentLength, Progress<ProgressContext> callback, ProgressContext context) {
        return onProgress(contentLength, true, callback, context);
    }

    public <ProgressContext> ControllableInputStream onProgress(int contentLength, boolean successful,
        Progress<ProgressContext> callback, ProgressContext context) {
        Validate.notNull(callback);
        Validate.notNull(context);
        return onProgress(contentLength, successful, () -> new Handle<>(callback, context));
    }

    /**
     * Binds a live source for the active progress handler. The source is re-read for every event, so it may return a
     * different handler to replace the current one, or {@code null} to cancel it, while the response is being read;
     * the previously bound handler then immediately stops receiving events. Observation counters and completion
     * start independently for this binding, from the current logical read position.
     */
    public ControllableInputStream onProgress(int contentLength, boolean successful,
        Supplier<? extends @Nullable Handle<?>> source) {
        this.contentLength = contentLength;
        this.successful = successful;
        this.progressSource = source;
        this.lastCallback = null;
        restartObservation(true);
        return this;
    }

    /** Resets per-observer progress state, optionally also clearing the entity-level declared-length mismatch. */
    private void restartObservation(boolean clearMismatch) {
        emittedInitial = false;
        completed = false;
        if (clearMismatch) lengthMismatch = false;
        lastEmitted = -1;
        highWater = readPos;
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

    /**
     * Pairs a progress callback with the context object supplied to it on every event. Part of the internal progress
     * plumbing; produced by the live handler source passed to {@link #onProgress(int, boolean, Supplier)}.
     */
    public static final class Handle<ProgressContext> {
        final Progress<ProgressContext> callback;
        final ProgressContext context;

        public Handle(Progress<ProgressContext> callback, ProgressContext context) {
            this.callback = callback;
            this.context = context;
        }
    }
}
