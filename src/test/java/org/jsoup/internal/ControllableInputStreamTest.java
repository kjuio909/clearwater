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
        in.onProgress(size, progress, in);

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
    void unknownLengthTotalStaysMinusOneAndCompletesOnceAtEof() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(-1, progress, in);

        readAll(in);

        progress.assertMonotonic();
        for (Event e : progress.events) assertEquals(-1, e.total, "an unknown total is always -1, even at completion");
        for (int i = 0; i < progress.events.size() - 1; i++)
            assertEquals(0f, progress.events.get(i).percent, "percent stays 0 until end");
        assertEquals(1, progress.countOf(100f));
        Event terminal = progress.last();
        assertEquals(size, terminal.processed, "processed is the actual delivered byte count");
        assertEquals(-1, terminal.total, "total is not resolved to the delivered length");
        assertEquals(100f, terminal.percent);
    }

    @Test
    void emptyEntityEmitsSingleDeterminableCompletion() throws IOException {
        // declared zero length: a single terminal event, no leading 0% event
        ControllableInputStream zero = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        ProgressEvents zeroProgress = new ProgressEvents();
        zero.onProgress(0, zeroProgress, zero);
        readAll(zero);
        readAll(zero); // a second drain must not emit a second completion
        assertEquals(1, zeroProgress.size(), "a declared-empty entity emits exactly one event");
        assertEquals(new Event(0, 0, 100f), zeroProgress.first());
        zero.close();

        // unknown length: an initial (0, -1, 0) followed by the single terminal completion
        ControllableInputStream unknown = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        ProgressEvents unknownProgress = new ProgressEvents();
        unknown.onProgress(-1, unknownProgress, unknown);
        readAll(unknown);
        readAll(unknown);
        assertEquals(-1, unknownProgress.first().total);
        assertEquals(0f, unknownProgress.first().percent);
        assertEquals(1, unknownProgress.countOf(100f), "exactly one completion");
        assertEquals(new Event(0, -1, 100f), unknownProgress.last(), "an unknown length is not resolved to 0");
        unknown.close();
    }

    @Test
    void speculativeMarkResetDoesNotRegressOrRezeroProgress() throws IOException {
        // mirrors DataUtil charset detection: a bounded speculative sniff under a mark, reset, then a full read
        int size = 20_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, progress, in);

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
        in.onProgress(10_000, progress, in);

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
        in.onProgress(size, progress, in);

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
    void declaredLengthLongerThanEntityNeverCompletes() throws IOException {
        int declared = 1000;
        int actual = 500;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[actual]), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(declared, progress, in); // the server promised more than it delivered

        readAll(in);

        progress.assertMonotonic();
        assertEquals(0, progress.countOf(100f), "a length mismatch must not report a false completion");
        Event last = progress.last();
        assertEquals(actual, last.processed, "the actually delivered bytes are reported honestly");
        assertEquals(declared, last.total, "the declared total is not rewritten");
        assertTrue(last.percent < 100f, "percent stays below 100: " + last.percent);
        in.close();
    }

    @Test
    void declaredLengthShorterThanEntityClampsAndNeverCompletes() throws IOException {
        int declared = 500;
        int actual = 900;
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[actual]), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(declared, progress, in); // the server delivered more than it declared

        int delivered = drainCount(in);
        assertEquals(actual, delivered, "the read itself delivers all bytes unchanged");

        progress.assertMonotonic();
        for (Event e : progress.events)
            assertTrue(e.processed <= declared, "processed must never exceed the declared length: " + e);
        assertEquals(0, progress.countOf(100f), "a length mismatch must not report a false completion");
        Event last = progress.last();
        assertEquals(declared, last.processed, "reported processed saturates at the declared length");
        assertEquals(declared, last.total);
        assertTrue(last.percent < 100f);
        in.close();
    }

    @Test
    void singleByteReadsAreCounted() throws IOException {
        int size = 300;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents progress = new ProgressEvents();
        in.onProgress(size, progress, in);

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
        in.onProgress(-1, (processed, total, percent, context) -> {
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
        in.onProgress(size, first, in);

        byte[] buf = new byte[100];
        in.read(buf); // drive some progress on the first callback
        int firstEvents = first.size();
        assertTrue(firstEvents > 0);

        ProgressEvents second = new ProgressEvents();
        in.onProgress(size, second, in); // register a fresh observer mid-response
        readAll(in);

        assertEquals(firstEvents, first.size(), "the replaced callback receives nothing further");
        assertEquals(1, second.countOf(100f), "the new callback sees its own completion");
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
    void failingHandlerNeverAbortsReadButKeepsReceivingLaterEvents() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress initialFail = new ExplodingProgress(1); // throws on the initial (0,total,0%) event
        in.onProgress(size, initialFail, in);

        int delivered = drainCount(in); // must not throw

        assertEquals(size, delivered, "the fetch continues and delivers every byte");
        assertTrue(initialFail.calls.get() > 1, "only the failing notification is skipped; later events still arrive");
        assertTrue(initialFail.sawCompletion.get(), "the completion event is still delivered to the same handler");
        in.close();

        // a fresh registration on a new stream starts independent: prior failure state does not leak
        ControllableInputStream again = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ProgressEvents fresh = new ProgressEvents();
        again.onProgress(size, fresh, again);
        assertEquals(size, drainCount(again));
        fresh.assertMonotonic();
        assertEquals(1, fresh.countOf(100f), "a new observer completes on its own state");
        assertEquals(size, fresh.last().processed);
        again.close();
    }

    @Test
    void handlerThrowingMidStreamSkipsOnlyThatNotification() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress midFail = new ExplodingProgress(2); // initial event OK, first data event throws
        in.onProgress(size, midFail, in);

        int delivered = drainCount(in); // must not throw and must read the whole entity

        assertEquals(size, delivered);
        assertTrue(midFail.calls.get() > 2, "events after the throwing one are still delivered");
        assertTrue(midFail.sawCompletion.get(), "the throw neither suppresses nor fabricates completion");
        in.close();
    }

    @Test
    void handlerThrowingOnEveryEventStillSeesCompletionAndReadIsUnaffected() throws IOException {
        int size = 1000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        Progress<Object> alwaysFail = new Progress<Object>() {
            int calls = 0;
            @Override public void onProgress(int processed, int total, float percent, Object context) {
                calls++;
                throw new RuntimeException("cannot stop progress: " + calls);
            }
        };
        in.onProgress(size, alwaysFail, new Object());

        assertEquals(size, drainCount(in)); // every throw is swallowed; bytes are still delivered
        in.close();
    }

    @Test
    void unknownLengthFailingHandlerStillReadsToEndAndGetsCompletion() throws IOException {
        int size = 10_000;
        byte[] data = new byte[size];
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        ExplodingProgress progress = new ExplodingProgress(1);
        in.onProgress(-1, progress, in); // unknown length

        assertEquals(size, drainCount(in));
        assertTrue(progress.calls.get() > 1, "the handler is not detached after throwing");
        assertTrue(progress.sawCompletion.get(), "completion at end-of-entity is still delivered");
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
