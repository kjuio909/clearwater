package org.jsoup.internal;

import org.jsoup.Progress;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControllableInputStreamProgressTest {

    /** A single recorded progress callback. */
    static final class Event {
        final int processed;
        final int total;
        final float percent;

        Event(int processed, int total, float percent) {
            this.processed = processed;
            this.total = total;
            this.percent = percent;
        }
    }

    private static List<Event> track(ControllableInputStream in, int contentLength, boolean successful) {
        List<Event> events = new ArrayList<>();
        Progress<Object> callback = (processed, total, percent, context) ->
            events.add(new Event(processed, total, percent));
        in.onProgress(contentLength, callback, new Object(), successful);
        return events;
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[7]; // deliberately awkward size
        while (in.read(buf) != -1) {
            // consume
        }
    }

    @Test void knownLengthMonotonicAndCompletesOnce() throws IOException {
        byte[] data = "0123456789ABCDEF".getBytes(); // 16 bytes
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, data.length, true);

        drain(in);
        in.read(new byte[4]); // further reads after EOF must not emit another completion
        in.close();

        Event first = events.get(0);
        assertEquals(0, first.processed);
        assertEquals(data.length, first.total);
        assertEquals(0f, first.percent, 0f);

        int completions = 0;
        int lastProcessed = -1;
        float lastPercent = -1f;
        for (Event e : events) {
            assertTrue(e.processed >= lastProcessed, "processed must never regress: " + events);
            assertTrue(e.percent >= lastPercent - 0.0001f, "percent must never regress: " + events);
            assertEquals(data.length, e.total, "total must stay the declared length");
            if (e.percent == 100f) completions++;
            lastProcessed = e.processed;
            lastPercent = e.percent;
        }
        assertEquals(1, completions, "exactly one 100% event");
        Event last = events.get(events.size() - 1);
        assertEquals(100f, last.percent, 0f);
        assertEquals(data.length, last.processed);
        assertEquals(data.length, last.total);
    }

    @Test void unknownLengthStaysMinusOneAndZeroUntilEof() throws IOException {
        byte[] data = "0123456789ABCDEF".getBytes();
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, -1, true);

        drain(in);
        in.close();

        int completions = 0;
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            boolean terminal = i == events.size() - 1;
            if (!terminal) {
                assertEquals(-1, e.total, "total stays -1 until completion");
                assertEquals(0f, e.percent, 0f, "percent stays 0 until completion");
                assertTrue(e.percent < 100f);
            }
            if (e.percent == 100f) {
                completions++;
                assertEquals(data.length, e.total, "terminal total is the measured length");
                assertEquals(data.length, e.processed);
            }
        }
        assertEquals(1, completions);
    }

    @Test void emptyEntityCompletes() throws IOException {
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        List<Event> known = track(in, 0, true);
        drain(in);
        in.close();

        long completesKnown = known.stream().filter(e -> e.percent == 100f).count();
        assertEquals(1, completesKnown);
        Event terminal = known.get(known.size() - 1);
        assertEquals(0, terminal.processed);
        assertEquals(100f, terminal.percent, 0f);

        // and when no length was declared for the empty body
        ControllableInputStream in2 = ControllableInputStream.wrap(new ByteArrayInputStream(new byte[0]), 0);
        List<Event> unknown = track(in2, -1, true);
        drain(in2);
        in2.close();
        Event terminal2 = unknown.get(unknown.size() - 1);
        assertEquals(0, terminal2.processed);
        assertEquals(0, terminal2.total);
        assertEquals(100f, terminal2.percent, 0f);
        assertEquals(1, unknown.stream().filter(e -> e.percent == 100f).count());
    }

    @Test void nonSuccessResponseNeverCompletesEvenWhenBodyFullyRead() throws IOException {
        byte[] data = "some error body".getBytes();
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, data.length, false);

        drain(in);
        in.close();

        assertTrue(events.size() > 0);
        for (Event e : events) {
            assertTrue(e.percent < 100f, "error body must never report 100%: " + e.percent);
        }
        assertEquals(data.length, events.get(events.size() - 1).processed, "bytes read are still reported");
    }

    @Test void prematureEofNeverCompletes() throws IOException {
        // server declared 64 bytes but sent 16 and then closed: an I/O failure shape
        byte[] data = "0123456789ABCDEF".getBytes();
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, 64, true);

        drain(in);
        in.close();

        for (Event e : events) {
            assertTrue(e.percent < 100f, "premature EOF must not report 100%");
            assertEquals(64, e.total, "declared total is retained");
        }
        Event last = events.get(events.size() - 1);
        assertEquals(16, last.processed, "the partial bytes are reported");
    }

    @Test void midReadIOExceptionReportsPartialAndNoCompletion() throws IOException {
        byte[] data = "0123456789ABCDEF".getBytes();
        InputStream failing = new FilterInputStream(new ByteArrayInputStream(data)) {
            int reads = 0;
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (++reads == 2) throw new IOException("connection reset by peer");
                return super.read(b, off, len);
            }
        };
        ControllableInputStream in = ControllableInputStream.wrap(failing, 0);
        List<Event> events = track(in, data.length, true);

        byte[] buf = new byte[8];
        assertThrows(IOException.class, () -> {
            while (in.read(buf) != -1) { /* consume */ }
        });
        for (Event e : events) assertTrue(e.percent < 100f);
        assertTrue(events.get(events.size() - 1).processed > 0, "some bytes were read before the failure");
    }

    @Test void resetDuringReadDoesNotRegressProgress() throws IOException {
        // models the charset detection path: mark, read a chunk, reset back, then read the whole stream
        byte[] data = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(); // 36
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, data.length, true);

        byte[] buf = new byte[8];
        in.mark(data.length);
        int first = in.read(buf); // 8
        assertEquals(8, first);
        in.reset();              // logical position back to 0; events must not go backwards

        drain(in);
        in.close();

        int lastProcessed = -1;
        float lastPercent = -1f;
        for (Event e : events) {
            assertTrue(e.processed >= lastProcessed, "processed regressed after reset: " + events);
            assertTrue(e.percent >= lastPercent - 0.0001f, "percent regressed after reset: " + events);
            lastProcessed = e.processed;
            lastPercent = e.percent;
        }
        assertEquals(1, events.stream().filter(e -> e.percent == 100f).count());
        assertEquals(data.length, events.get(events.size() - 1).processed);
    }

    @Test void singleByteReadsAndSkipsAreCounted() throws IOException {
        byte[] data = "0123456789".getBytes();
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 0);
        List<Event> events = track(in, data.length, true);

        assertEquals('0', in.read());
        assertEquals('1', in.read());
        assertEquals(3L, in.skip(3)); // skips 2,3,4
        byte[] rest = new byte[10];
        int read;
        int total = 5;
        while ((read = in.read(rest, 0, rest.length)) != -1) total += read;
        assertEquals(data.length, total);
        in.close();

        Event last = events.get(events.size() - 1);
        assertEquals(100f, last.percent, 0f);
        assertEquals(data.length, last.processed);
    }

    @Test void freshStreamDoesNotLeakPreviousProgress() throws IOException {
        byte[] firstData = "0123456789".getBytes();
        ControllableInputStream first = ControllableInputStream.wrap(new ByteArrayInputStream(firstData), 0);
        List<Event> firstEvents = track(first, firstData.length, true);
        drain(first);
        first.close();
        assertEquals(100f, firstEvents.get(firstEvents.size() - 1).percent, 0f);

        // a brand new wrapped stream starts counting from zero regardless of any prior stream
        byte[] secondData = "abc".getBytes();
        ControllableInputStream second = ControllableInputStream.wrap(new ByteArrayInputStream(secondData), 0);
        List<Event> secondEvents = track(second, secondData.length, true);
        drain(second);
        second.close();

        assertEquals(0, secondEvents.get(0).processed);
        Event last = secondEvents.get(secondEvents.size() - 1);
        assertEquals(3, last.processed);
        assertEquals(100f, last.percent, 0f);
    }

    @Test void capHitIsNotCompletionButConfirmedEofIs() throws IOException {
        byte[] data = "0123456789".getBytes(); // 10 bytes
        ControllableInputStream in = ControllableInputStream.wrap(new ByteArrayInputStream(data), 5);
        List<Event> events = track(in, data.length, true);

        byte[] buf = new byte[10];
        assertEquals(5, in.read(buf));
        assertEquals(-1, in.read(buf)); // synthetic EOF from the cap; entity not confirmed fully read
        for (Event e : events) assertTrue(e.percent < 100f, "a cap hit is not completion");
        in.close();
    }
}
