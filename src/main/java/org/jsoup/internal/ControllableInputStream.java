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
    private boolean allowClose = true;      // for cases where we want to re-read the input, can ignore .close() from the parser

    // if we are tracking progress, will have the expected content length and progress callback state
    private @Nullable ProgressState<?> progress;
    private int expectedLength = -1;      // declared content length for progress; -1 == unknown, never mutated on completion
    private int readPos = 0;              // logical amount read; can be reset() by mark/reset
    private int reportedPos = 0;          // high-water mark ever emitted to the progress callback; monotonic
    private boolean progressComplete = false; // latches the single terminal progress callback
    private boolean reportCompletion = true;   // false for non-success responses: partial progress is allowed, but never 100%

    private final byte[] singleByte = new byte[1]; // routes the single byte read() through the controlled read(byte[], ...)

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
        // route the single byte read through the controlled array read, so that caps, timeouts, interrupts, and
        // progress are applied consistently
        int read = read(singleByte, 0, 1);
        return read == -1 ? -1 : singleByte[0] & 0xff;
    }

    @Override
    public long skip(long n) throws IOException {
        // route skips through the controlled read, so that skipped bytes are still capped and counted in progress
        if (n <= 0) return 0;
        long skipped = 0;
        byte[] skipBuf = SimpleBufferedInput.BufferPool.borrow();
        try {
            while (skipped < n) {
                int size = (int) Math.min(n - skipped, skipBuf.length);
                int read = read(skipBuf, 0, size);
                if (read == -1) break;
                skipped += read;
                if (read < size) break;
            }
        } finally {
            SimpleBufferedInput.BufferPool.release(skipBuf);
        }
        return skipped;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (readPos == 0 && reportedPos == 0) emitProgress(false); // emits the initial, zero-processed event

        boolean capped = maxSize != 0;
        if (interrupted || capped && remaining <= 0) {
            // cap hit or interrupt is normally a synthetic EOF, not a confirmed end of entity. But if the buffer
            // already knows the underlying entity reached EOF, this is a genuine completion and must be reported once.
            if (!interrupted && capped && remaining <= 0 && progress != null && !progressComplete && buff.baseReadFully())
                emitProgress(true);
            return -1;
        }
        if (Thread.currentThread().isInterrupted()) {
            // interrupted latches, because parse() may call twice
            interrupted = true;
            return -1;
        }

        if (capped && len > remaining)
            len = remaining; // don't read more than desired, even if available
        buff.capRemaining(capped ? remaining : Integer.MAX_VALUE);

        while (true) { // loop trying to read until we get some data or hit the overall timeout, if we have one
            if (expired())
                throw new SocketTimeoutException("Read timeout");

            try {
                final int read = super.read(b, off, len);
                if (read == -1) { // the underlying entity was read to its end: emit the single completion event
                    emitProgress(true);
                } else {
                    if (capped && read > 0) {
                        remaining -= read; // track bytes returned to the caller
                    }
                    readPos = saturatingAdd(readPos, read);
                    emitProgress(false);
                }
                return read;
            } catch (SocketTimeoutException e) {
                if (expired() || timeout == 0)
                    throw e;
            }
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
        if (maxSize != 0) {
            remaining = maxSize - markPos;
            buff.capRemaining(remaining);
        } else {
            remaining = 0;
            buff.capRemaining(Integer.MAX_VALUE);
        }
        readPos = markPos; // readPos tracks the logical read position for cap and progress emits
        markPos = -1;
    }

    @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod") // not synchronized in later JDKs
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
        maxSize = newMax;
        buff.capRemaining(newMax == 0 ? Integer.MAX_VALUE : remaining);
    }

    public void allowClose(boolean allowClose) {
        this.allowClose = allowClose;
    }

    @Override public void close() throws IOException {
        if (allowClose) {
            // If the whole entity had already been pulled from the underlying stream but no terminal read happened
            // (e.g. readFully stops as soon as it exhausts the size cap), still deliver the single completion event.
            // A partial read that is closed early has baseReadFully() false, so no completion is faked.
            if (progress != null && buff.baseReadFully())
                emitProgress(true);
            super.close();
        }
    }

    public ControllableInputStream timeout(long startTimeNanos, long timeoutMillis) {
        this.startTime = startTimeNanos;
        this.timeout = timeoutMillis * 1000000;
        return this;
    }

    /**
     Report progress using the logical read position, clamped against the high-water mark so that re-reads after a
     mark/reset (used during charset detection) never emit a smaller {@code processed} or lower {@code percent} than
     a previous event. The terminal event is emitted exactly once:
     <ul>
       <li>known length: the high-water mark reaches the declared length (or the stream confirms EOF at it);</li>
       <li>unknown length: percent stays at 0 until the underlying stream confirms EOF, when a single 100% event
       is emitted with {@code total} set to the final byte count;</li>
     </ul>
     A synthetic EOF (max size cap hit, or thread interrupt) does <b>not</b> raise completion, because the entity
     may continue beyond the truncated read.
     @param complete true only when the underlying entity is confirmed read to its end
     */
    private void emitProgress(boolean complete) {
        ProgressState<?> progress = this.progress;
        if (progress == null) return;

        if (readPos > reportedPos) reportedPos = readPos; // clamp to the high-water mark; reset() must not regress events
        int processed = reportedPos;
        int total = expectedLength;
        float percent;

        // A confirmed EOF must not be reported as 100% when:
        //  - this is a non-success HTTP response (an error drain is not a successful delivery), or
        //  - a length was declared and fewer bytes arrived (premature EOF / mid-entity I/O failure).
        // In those cases report the bytes actually read once, but never signal completion, then detach.
        boolean prematureEof = complete && expectedLength > 0 && processed < expectedLength;
        if (complete && (!reportCompletion || prematureEof)) {
            percent = expectedLength > 0 ? Math.min(100f, processed * 100f / expectedLength) : 0f;
            if (percent >= 100f) percent = 99.99999f;
            progress.emit(processed, total, percent);
            this.progress = null;
            return;
        }

        if (complete) {
            if (expectedLength < 0) total = processed; // unknown length: only now reveal the measured total
            percent = 100.0f;
            progressComplete = true;
        } else if (expectedLength > 0) {
            percent = Math.min(100f, processed * 100f / expectedLength);
        } else {
            percent = 0f;
        }

        // only the confirmed-EOF event may report 100%; otherwise an exact-length buffer fill followed by the EOF
        // read would look like two completion callbacks
        if (!complete && percent >= 100f) percent = 99.99999f;

        progress.emit(processed, total, percent);
        if (progressComplete)
            this.progress = null; // detach after the single terminal event, so later reads can't report completion again
    }

    private static int saturatingAdd(int value, int add) {
        long sum = (long) value + add;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /**
     Attach a progress callback to this stream.
     @param contentLength the declared entity length, or -1 if unknown
     @param successful false for a non-success HTTP status response: partial progress may then be reported as the
     error body is consumed, but a terminal 100% completion event is never emitted
     */
    public <ProgressContext> ControllableInputStream onProgress(int contentLength, Progress<ProgressContext> callback, ProgressContext context, boolean successful) {
        Validate.notNull(callback);
        Validate.notNull(context);
        this.expectedLength = contentLength;
        this.readPos = 0;
        this.reportedPos = 0;
        this.progressComplete = false;
        this.reportCompletion = successful;
        this.progress = new ProgressState<>(callback, context);
        return this;
    }

    /**
     Attach a progress callback to this stream, treating the entity as a successful response.
     @since 1.18.1
     */
    public <ProgressContext> ControllableInputStream onProgress(int contentLength, Progress<ProgressContext> callback, ProgressContext context) {
        return onProgress(contentLength, callback, context, true);
    }

    private boolean expired() {
        if (timeout == 0)
            return false;

        final long now = System.nanoTime();
        final long dur = now - startTime;
        return (dur > timeout);
    }

    public BufferedInputStream inputStream() {
        // called via HttpConnection.Response.bodyStream(), needs an OG BufferedInputStream
        return new BufferedInputStream(buff);
    }

    private static class ProgressState<ProgressContext> {
        private final Progress<ProgressContext> callback;
        private final ProgressContext context;

        ProgressState(Progress<ProgressContext> callback, ProgressContext context) {
            this.callback = callback;
            this.context = context;
        }

        void emit(int processed, int total, float percent) {
            callback.onProgress(processed, total, percent, context);
        }
    }
}
