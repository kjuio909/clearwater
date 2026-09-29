package org.jsoup.internal;

import org.jsoup.Progress;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ControllableInputStreamTest {

    @Test
    void respectsMaxCapDuringFill() throws IOException {
        byte[] data = "0123456789".getBytes(); // 10 bytes
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(data));

        ControllableInputStream in = ControllableInputStream.wrap(counting, 5); // cap at 5 bytes
        byte[] buf = new byte[10];

        int read = in.read(buf);
        assertEquals(5, read, "should only read up to cap");
        assertEquals(5, counting.count, "underlying stream should not be pulled past cap");
        assertFalse(in.baseReadFully(), "cap hit is not EOF");

        int second = in.read(buf);
        assertEquals(-1, second, "further reads return -1 once cap is exhausted");
        assertFalse(in.baseReadFully(), "still not true EOF");
        assertTrue(in.isTruncated(), "content beyond the cap is a truncation");
        in.close();
    }

    @Test
    void compactsBufferWithActiveMark() throws IOException {
        int size = SharedConstants.DefaultBufferSize * 2;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i % 256);

        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);

        byte[] first = new byte[500];
        assertEquals(500, in.read(first));

        in.mark(SharedConstants.DefaultBufferSize); // mark at logical pos 500

        byte[] consume = new byte[SharedConstants.DefaultBufferSize];
        int firstRead = in.read(consume); // serves remainder of current buffer (BufferSize - 500)
        assertEquals(SharedConstants.DefaultBufferSize - 500, firstRead);

        byte[] more = new byte[1000];
        int secondRead = in.read(more); // triggers fill() with active mark, then consumes from freshly filled buffer
        assertEquals(SharedConstants.DefaultBufferSize - firstRead, secondRead);

        in.reset(); // should rewind to mark despite prior compaction

        byte[] reread = new byte[1000];
        assertEquals(1000, in.read(reread));
        for (int i = 0; i < reread.length; i++) {
            assertEquals(data[500 + i], reread[i], "byte mismatch at " + i);
        }
        in.close();
    }

    // ===== Progress semantics =====

    /** records every progress event for assertions */
    private static final class ProgressEvents implements Progress<ControllableInputStream> {
        final List<Event> events = new ArrayList<>();

        @Override public void onProgress(int processed, int total, float percent, ControllableInputStream context) {
            events.add(new Event(processed, total, percent));
        }

        int size() { return events.size(); }
        Event last() { return events.get(events.size() - 1); }
        Event first() { return events.get(0); }

        long countOf(float percent) {
            return events.stream().filter(e -> e.percent == percent).count();
        }

        void assertMonotonic() {
            int lastProcessed = -1;
            float lastPercent = -1f;
            for (Event e : events) {
                assertTrue(e.processed >= lastProcessed, "processed went backwards: " + lastProcessed + " -> " + e.processed);
                assertTrue(e.percent >= lastPercent, "percent went backwards: " + lastPercent + " -> " + e.percent);
                lastProcessed = e.processed;
                lastPercent = e.percent;
            }
        }
    }

    private static final class Event {
        final int processed;
        final int total;
        final float percent;

        Event(int processed, int total, float percent) {
            this.processed = processed;
            this.total = total;
            this.percent = percent;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Event)) return false;
            Event event = (Event) o;
            return processed == event.processed && total == event.total && Float.compare(event.percent, percent) == 0;
        }

        @Override public int hashCode() {
            return java.util.Objects.hash(processed, total, percent);
        }

        @Override public String toString() {
            return "Event{processed=" + processed + ", total=" + total + ", percent=" + percent + '}';
        }
    }

    private static void readAll(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        while (in.read(buf) != -1) {
            // drain
        }
    }

    @Test
    void knownLengthProgressIsMonotonicAndCompletesOnce() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, true, progress, in);

        readAll(in);

        assertTrue(progress.size() >= 2, "expected an initial and a terminal event at minimum");
        assertEquals(new Event(0, size, 0f), progress.first());
        progress.assertMonotonic();
        for (Event e : progress.events) assertEquals(size, e.total, "declared total must hold");
        assertEquals(1, progress.countOf(100f), "exactly one completion event");
        Event terminal = progress.last();
        assertEquals(size, terminal.processed);
        assertEquals(size, terminal.total);
        assertEquals(100f, terminal.percent);
    }

    @Test
    void unknownLengthStaysAtZeroUntilSingleCompletion() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(-1, true, progress, in);

        readAll(in);

        progress.assertMonotonic();
        for (Event e : progress.events)
            assertEquals(-1, e.total, "an unknown length stays -1 on every event, including completion");
        for (int i = 0; i < progress.events.size() - 1; i++) {
            Event e = progress.events.get(i);
            assertEquals(0f, e.percent, "percent stays 0 until the confirmed end");
        }
        assertEquals(1, progress.countOf(100f));
        Event terminal = progress.last();
        assertEquals(size, terminal.processed, "processed carries the actual delivered length");
        assertEquals(-1, terminal.total, "total remains -1 even at completion");
        assertEquals(100f, terminal.percent);
    }

    @Test
    void emptyEntityEmitsSingleDeterminableCompletion() throws IOException {
        // declared zero length: a single terminal event, no leading 0% event
        ControllableInputStream zero = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        ProgressEvents zeroProgress = new ProgressEvents();
        zero.onProgress(0, true, zeroProgress, zero);
        readAll(zero);
        readAll(zero); // a second drain must not emit a second completion
        assertEquals(1, zeroProgress.size(), "a declared-empty entity emits exactly one event");
        assertEquals(new Event(0, 0, 100f), zeroProgress.first());
        zero.close();

        // unknown length: no determinable start, so an empty entity is just its single terminal completion, total -1
        ControllableInputStream unknown = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        ProgressEvents unknownProgress = new ProgressEvents();
        unknown.onProgress(-1, true, unknownProgress, unknown);
        readAll(unknown);
        readAll(unknown);
        assertEquals(1, unknownProgress.size(), "an unknown-length empty entity emits exactly one event");
        assertEquals(new Event(0, -1, 100f), unknownProgress.first());
        unknown.close();
    }

    @Test
    void speculativeMarkResetDoesNotRegressOrRezeroProgress() throws IOException {
        // mirrors DataUtil charset detection: a bounded speculative sniff under a mark, reset, then a full read
        int size = 20_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, true, progress, in);

        byte[] buf = new byte[8192];
        in.max(5120);
        in.mark(5120);
        while (in.read(buf) != -1) { } // sniff up to the cap
        in.reset();
        in.max(0); // lift the cap and read the whole entity
        readAll(in);

        progress.assertMonotonic();
        assertEquals(1, progress.countOf(100f), "a single completion despite the re-read");
        assertEquals(size, progress.last().processed);
        in.close();
    }

    @Test
    void midReadFailureReportsPartialButNeverCompletion() throws IOException {
        int delivered = 3000;
        ControllableInputStream in = ControllableInputStream.wrap(new FailingInputStream(delivered), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(10_000, true, progress, in);

        IOException thrown = assertThrows(IOException.class, () -> readAll(in));
        assertEquals("boom", thrown.getMessage());

        progress.assertMonotonic();
        assertEquals(0, progress.countOf(100f), "a failed read never reports 100%");
        assertTrue(progress.last().processed <= delivered);
        assertTrue(progress.last().processed > 0, "the actually read partial bytes may be reported");
        assertTrue(progress.last().percent < 100f);
        assertEquals(10_000, progress.last().total, "declared total unchanged on failure");
    }

    @Test
    void truncationAtCapIsNotCompletionThenCompletesWhenCapLifted() throws IOException {
        int size = 20;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 10);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, true, progress, in);

        byte[] buf = new byte[100];
        int first = in.read(buf);
        assertEquals(10, first);
        assertEquals(-1, in.read(buf));
        assertTrue(in.isTruncated());
        assertEquals(0, progress.countOf(100f), "hitting the body cap is not a completion");
        assertEquals(10, progress.last().processed);
        assertTrue(progress.last().percent < 100f);

        in.max(size); // lift the cap and finish the entity
        readAll(in);
        assertEquals(1, progress.countOf(100f));
        assertEquals(size, progress.last().processed);
        in.close();
    }

    @Test
    void unsuccessfulStatusNeverReportsCompletion() throws IOException {
        int size = 500;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, false, progress, in); // e.g. an error status delivered with ignoreHttpErrors

        readAll(in);

        progress.assertMonotonic();
        assertEquals(0, progress.countOf(100f), "an error response must never signal completion");
        assertEquals(size, progress.last().processed, "delivered bytes are still reported");
        assertTrue(progress.last().percent < 100f);
        assertEquals(size, progress.last().total);
    }

    @Test
    void singleByteReadsAreCounted() throws IOException {
        int size = 300;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, true, progress, in);

        while (in.read() != -1) { } // byte-by-byte

        assertEquals(1, progress.countOf(100f));
        assertEquals(size, progress.last().processed);
        in.close();
    }

    @Test
    void largeReadSaturatesProgressWithoutOverflow() throws IOException {
        long size = (long) Integer.MAX_VALUE + 1;
        ControllableInputStream in = ControllableInputStream.wrap(new VirtualInputStream(size), 0);
        AtomicInteger lastProcessed = new AtomicInteger(-1);
        AtomicBoolean negative = new AtomicBoolean(false);
        AtomicBoolean completed = new AtomicBoolean(false);
        in.onProgress(-1, true, (processed, total, percent, context) -> {
            if (processed < 0) negative.set(true);
            if (percent == 100f) completed.set(true);
            lastProcessed.set(processed);
        }, in);

        assertEquals(size, in.skip(size)); // skip routes through the controlled read, so progress is accounted for
        assertEquals(-1, in.read());
        assertFalse(negative.get(), "processed must not overflow to a negative value");
        assertTrue(completed.get());
        assertEquals(Integer.MAX_VALUE, lastProcessed.get());
    }

    @Test
    void replacingCallbackObservesIndependentlyAndOldOneStops() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents first = new ProgressEvents();
        in.onProgress(size, true, first, in);

        byte[] buf = new byte[100];
        in.read(buf); // drive some progress on the first callback
        int firstEvents = first.size();
        assertTrue(firstEvents > 0);

        ProgressEvents second = new ProgressEvents();
        in.onProgress(size, true, second, in); // register a fresh observer mid-response
        readAll(in);

        assertEquals(firstEvents, first.size(), "the replaced callback receives nothing further");
        assertEquals(1, second.countOf(100f), "the new callback sees its own completion");
        assertEquals(size, second.last().processed);
        in.close();
    }

    @Test
    void declaredLengthLongerThanEntityNeverCompletes() throws IOException {
        int actual = 5_000;
        int declared = 10_000;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[actual]), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(declared, true, progress, in);

        readAll(in);

        progress.assertMonotonic();
        assertEquals(0, progress.countOf(100f), "an entity shorter than its declared length never completes");
        assertEquals(actual, progress.last().processed, "the bytes actually delivered are still reported");
        assertEquals(declared, progress.last().total, "the declared total is held");
        assertTrue(progress.last().percent < 100f);
        in.close();
    }

    @Test
    void declaredLengthShorterThanEntityClampsProcessedAndNeverCompletes() throws IOException {
        int actual = 10_000;
        int declared = 5_000;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[actual]), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(declared, true, progress, in);

        readAll(in);

        for (Event e : progress.events) {
            assertTrue(e.processed <= declared, "processed never exceeds the declared length");
            assertEquals(declared, e.total, "the declared total is held");
        }
        assertEquals(0, progress.countOf(100f), "extra bytes beyond the declared length never complete");
        assertEquals(declared, progress.last().processed);
        assertTrue(progress.last().percent < 100f);
        in.close();
    }

    @Test
    void cancellingHandlerLiveStopsEventsButReadContinues() throws IOException {
        int size = 10_000;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[size]), 0);
        ProgressEvents first = new ProgressEvents();
        AtomicReference<ControllableInputStream.Handle<?>> active =
            new AtomicReference<>(new ControllableInputStream.Handle<>(first, in));
        in.onProgress(size, true, active::get); // a live source: returning null cancels the handler mid-response

        byte[] buf = new byte[100];
        in.read(buf);
        int beforeCancel = first.size();
        assertTrue(beforeCancel > 0);

        active.set(null); // cancel without touching the stream
        int rest = drainCount(in);

        assertEquals(size - 100, rest, "the read still runs to the end after cancellation");
        assertEquals(beforeCancel, first.size(), "a cancelled handler gets no further events, including no completion");
        assertEquals(0, first.countOf(100f));
        in.close();
    }

    @Test
    void replacingHandlerLiveDeliversOnlyToTheCurrentOne() throws IOException {
        int size = 10_000;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[size]), 0);
        ProgressEvents first = new ProgressEvents();
        AtomicReference<ControllableInputStream.Handle<?>> active =
            new AtomicReference<>(new ControllableInputStream.Handle<>(first, in));
        in.onProgress(size, true, active::get);

        byte[] buf = new byte[100];
        in.read(buf); // drive some progress on the first handler
        int firstEvents = first.size();
        assertTrue(firstEvents > 0);

        ProgressEvents second = new ProgressEvents();
        active.set(new ControllableInputStream.Handle<>(second, in)); // swap live via the source
        readAll(in);

        assertEquals(firstEvents, first.size(), "the old handler receives nothing after the live swap");
        assertEquals(1, second.countOf(100f), "the new handler sees its own completion");
        assertEquals(size, second.last().processed);
        in.close();
    }

    // ===== Progress handler fault isolation =====

    /** throws a RuntimeException on its nth (1-based) event, but counts every event it actually receives */
    private static final class ExplodingProgress implements Progress<ControllableInputStream> {
        final AtomicInteger calls = new AtomicInteger();
        final int failOnCall;
        final AtomicBoolean sawCompletion = new AtomicBoolean(false);

        ExplodingProgress(int failOnCall) {
            this.failOnCall = failOnCall;
        }

        @Override public void onProgress(int processed, int total, float percent, ControllableInputStream context) {
            int call = calls.incrementAndGet();
            if (percent == 100f) sawCompletion.set(true);
            if (call == failOnCall) throw new IllegalStateException("boom from progress handler");
        }
    }

    private static int drainCount(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(buf)) != -1) total += read;
        return total;
    }

    @Test
    void failingHandlerOnAnEventKeepsReceivingLaterEventsAndStillCompletes() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress initialFail = new ExplodingProgress(1); // throws on the initial (0,total,0%) event
        in.onProgress(size, true, initialFail, in);

        int delivered = drainCount(in); // must not throw

        assertEquals(size, delivered, "the fetch continues and delivers every byte");
        assertTrue(initialFail.calls.get() > 1, "a throw suppresses only that notification; later events still arrive");
        assertTrue(initialFail.sawCompletion.get(), "the terminal completion is still delivered despite the throw");
        in.close();

        // a fresh registration on another stream starts independently: prior failure state does not leak
        ControllableInputStream again = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents fresh = new ProgressEvents();
        again.onProgress(size, true, fresh, again);
        assertEquals(size, drainCount(again));
        fresh.assertMonotonic();
        assertEquals(1, fresh.countOf(100f), "a new observer completes on its own state");
        assertEquals(size, fresh.last().processed);
        again.close();
    }

    @Test
    void handlerThrowingMidStreamDoesNotStopLaterEventsOrCompletion() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress midFail = new ExplodingProgress(2); // initial event OK, first data event throws
        in.onProgress(size, true, midFail, in);

        int delivered = drainCount(in); // must not throw and must read the whole entity

        assertEquals(size, delivered);
        assertTrue(midFail.calls.get() > 2, "events keep flowing after the one that threw");
        assertTrue(midFail.sawCompletion.get(), "the completion event still reaches the handler");
        in.close();
    }

    @Test
    void handlerThrowingOnTerminalEventIsSwallowed() throws IOException {
        int size = 1000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        Progress<Object> terminalFail = new Progress<Object>() {
            int calls = 0;
            @Override public void onProgress(int processed, int total, float percent, Object context) {
                calls++;
                if (percent == 100f) throw new RuntimeException("cannot stop completion");
            }
        };
        in.onProgress(size, true, terminalFail, new Object());

        assertEquals(size, drainCount(in)); // the terminal throw must not escape the final read
        in.close();
    }

    @Test
    void unknownLengthFailingHandlerStillReadsToEndAndCompletes() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress progress = new ExplodingProgress(1);
        in.onProgress(-1, true, progress, in); // unknown length

        assertEquals(size, drainCount(in));
        assertTrue(progress.calls.get() > 1, "the throw suppresses only one notification; later events still arrive");
        assertTrue(progress.sawCompletion.get(), "the unknown-length completion is still reported");
        in.close();
    }

    private static final class CountingInputStream extends FilterInputStream {
        int count = 0;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }

        @Override
        public int read() throws IOException {
            int r = super.read();
            if (r != -1) count++;
            return r;
        }
    }

    /** delivers {@code failAfter} zero bytes, then raises an IOException */
    private static final class FailingInputStream extends InputStream {
        private int remaining;

        FailingInputStream(int failAfter) {
            this.remaining = failAfter;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("boom");
            int read = Math.min(remaining, len);
            remaining -= read;
            return read;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) throw new IOException("boom");
            remaining--;
            return 0;
        }
    }

    private static final class VirtualInputStream extends InputStream {
        private long remaining;

        VirtualInputStream(long size) {
            remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int read = (int) Math.min(remaining, length);
            remaining -= read;
            return read;
        }
    }
}
