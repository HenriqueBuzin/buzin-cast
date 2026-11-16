package io.buzincast.fireos3probe;

import java.util.ArrayDeque;

/** Maps only timestamped PCM accepted by AudioTrack to its hardware playback head. */
final class AudioPlaybackClock {
    static final long UNKNOWN = Long.MIN_VALUE;
    private static final int RATE = 44100, MAX_SPANS = 128;
    private static final long STALE_NS = 250000000L;
    private final ArrayDeque<Span> spans = new ArrayDeque<>();
    private long raw, wrap, head, stamp, lastEnd;
    private long generation;
    private boolean initialized, invalid;
    private boolean hasKnown;
    private long knownStamp;
    private static final class Span {
        final long first, end, pts;
        Span(long first, long frames, long pts) { this.first=first; this.end=first+frames; this.pts=pts; }
    }
    synchronized void reset() {
        generation++;
        spans.clear(); raw=wrap=head=stamp=lastEnd=knownStamp=0; initialized=invalid=hasKnown=false;
    }
    synchronized long generation() { return generation; }
    synchronized boolean recentlyActive(long nowNs) {
        return hasKnown && !invalid && nowNs>=knownStamp && nowNs-knownStamp<=STALE_NS;
    }
    private long known(long position,long nowNs) { hasKnown=true;knownStamp=nowNs;return position; }
    synchronized String describe(long nowNs) {
        return "generation="+generation+", initialized="+initialized+", invalid="+invalid+
            ", head="+head+", raw="+raw+", end="+lastEnd+", spans="+spans.size()+
            ", first="+(spans.isEmpty()?-1:spans.peekFirst().first)+", ageMs="+(nowNs-stamp)/1000000;
    }
    synchronized void submit(long firstFrame, long frames, long ptsNs) {
        if (firstFrame < 0 || frames <= 0 || frames > 4096 || firstFrame > Long.MAX_VALUE-frames ||
                ptsNs <= 0 || ptsNs > Long.MAX_VALUE-1000000000L || firstFrame < lastEnd) return;
        if (spans.size() == MAX_SPANS) spans.removeFirst();
        spans.addLast(new Span(firstFrame, frames, ptsNs)); lastEnd=firstFrame+frames;
    }
    synchronized long updateHead(long rawPosition, long nowNs) {
        long value = rawPosition & 0xffffffffL;
        if (initialized && value < raw) {
            if (raw-value > 0x80000000L) wrap += 1L<<32;
            else invalid=true; // an unexpected reset cannot safely reuse old frame indices
        }
        // Old AudioTrack implementations publish the counter in coarse blocks.
        // Re-reading the same counter must not rewind the extrapolated clock or
        // indefinitely refresh a stalled device's timestamp.
        if(!initialized || value!=raw)stamp=nowNs;
        raw=value; head=wrap+value; initialized=true;
        while (!spans.isEmpty() && spans.peekFirst().end <= head) spans.removeFirst();
        return head;
    }
    synchronized long positionNs(long nowNs) {
        long elapsed = nowNs-stamp;
        if (!initialized || invalid || spans.isEmpty() || elapsed < 0 || elapsed > STALE_NS) return UNKNOWN;
        // Keep the prediction on accepted timestamped PCM until a fresh hardware
        // count confirms that playback crossed its end. A coarse count alone
        // cannot prove an underrun. STALE_NS still bounds a stationary counter.
        long estimated = Math.min(lastEnd-1, head + elapsed*RATE/1000000000L);
        for (Span span : spans) {
            if (estimated < span.first) {
                // Count down toward known upcoming PCM, also after an underrun.
                // Use that block's sample rate, never interpolate between discontinuous PTS.
                if (span.first-estimated <= RATE/4) {
                    long predicted = span.pts-duration(span.first-estimated);
                    return predicted > 0 ? known(predicted,nowNs) : UNKNOWN;
                }
                return UNKNOWN;
            }
            if (estimated < span.end) return known(span.pts+duration(estimated-span.first),nowNs);
        }
        return UNKNOWN;
    }
    synchronized long delayNs(long videoPtsNs, long nowNs) {
        if (videoPtsNs <= 0) return UNKNOWN;
        long audio = positionNs(nowNs);
        return audio == UNKNOWN ? UNKNOWN : videoPtsNs-audio;
    }
    private static long duration(long frames) {
        return frames/RATE*1000000000L + frames%RATE*1000000000L/RATE;
    }
}
