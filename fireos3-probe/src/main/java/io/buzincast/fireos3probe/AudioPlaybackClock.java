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
    private static final class Span {
        final long first, end, pts;
        Span(long first, long frames, long pts) { this.first=first; this.end=first+frames; this.pts=pts; }
    }
    synchronized void reset() {
        generation++;
        spans.clear(); raw=wrap=head=stamp=lastEnd=0; initialized=invalid=false;
    }
    synchronized long generation() { return generation; }
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
        raw=value; head=wrap+value; stamp=nowNs; initialized=true;
        while (!spans.isEmpty() && spans.peekFirst().end <= head) spans.removeFirst();
        return head;
    }
    synchronized long positionNs(long nowNs) {
        long elapsed = nowNs-stamp;
        if (!initialized || invalid || spans.isEmpty() || elapsed < 0 || elapsed > STALE_NS) return UNKNOWN;
        long estimated = Math.min(lastEnd, head + elapsed*RATE/1000000000L);
        for (Span span : spans) {
            if (estimated < span.first) {
                // Count down toward known upcoming PCM, also after an underrun.
                // Use that block's sample rate, never interpolate between discontinuous PTS.
                if (span.first-estimated <= RATE/4) {
                    long predicted = span.pts-duration(span.first-estimated);
                    return predicted > 0 ? predicted : UNKNOWN;
                }
                return UNKNOWN;
            }
            if (estimated < span.end) return span.pts+duration(estimated-span.first);
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
