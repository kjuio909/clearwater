package org.jsoup;

@FunctionalInterface

public interface Progress<ProgressContext> {
    /**
     Called to report progress. Note that this will be executed by the same thread that is doing the work, so either
     don't take to long, or hand it off to another thread.
     <p>Events are delivered in read order: {@code processed} and {@code percent} never decrease. The terminal
     event ({@code percent ==} {@code 100.0}) is delivered exactly once, only after the work has actually
     completed &mdash; an error or a partial failure never reports completion.</p>
     @param processed the number of bytes processed so far.
     @param total the total number of expected bytes, or -1 if unknown. When the total is unknown it stays -1 (and
     {@code percent} stays 0) until completion, where it is set to the final byte count.
     @param percent the percentage of completion, 0.0..100.0. If the expected total is unknown, % will remain at zero
     until complete.
     @param context the object that progress was made on.
     @since 1.18.1
     */
    void onProgress(int processed, int total, float percent, ProgressContext context);
}
