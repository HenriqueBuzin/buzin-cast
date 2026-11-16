#ifndef PCM_TIMELINE_CLOCK_H
#define PCM_TIMELINE_CLOCK_H

#include <algorithm>
#include <cstdint>
#include <cstddef>
#include <deque>
#include <limits>
#include <vector>

// Metadata only; PCM remains in TimelineBuffer. The API17 owner serializes
// producer/consumer access. Absolute ring positions preserve discontinuities,
// overflow, inserted gaps and consumer trims without guessing from backlog ms.
class PcmTimelineClock {
public:
    PcmTimelineClock(int rate, int channels) : mRate(rate), mChannels(channels) {}
    void enqueued(uint64_t first, size_t samples, int64_t pts) {
        if (!samples) return;
        if (!mSpans.empty()) {
            Span &last = mSpans.back();
            if (first < last.end) mSpans.clear();
            else if (first == last.end && contiguous(last, pts)) { last.end += samples; return; }
        }
        if (mSpans.size() == MAX_SPANS) mSpans.pop_front();
        mSpans.push_back({first, first+samples, pts});
    }

    // Triplets: offset in output frames, valid frames, first frame's local NTP ns.
    // Priming/underrun padding has no span and cannot become an audio clock.
    std::vector<int64_t> read(uint64_t first, size_t samples) {
        while (!mSpans.empty() && mSpans.front().end <= first) mSpans.pop_front();
        std::vector<int64_t> result;
        const uint64_t end = first+samples;
        for (const Span &span : mSpans) {
            if (span.first >= end) break;
            if (span.pts <= 0) continue;
            const uint64_t start = std::max(first, span.first), limit = std::min(end, span.end);
            const int64_t delta = duration(start-span.first);
            if (limit <= start || span.pts > std::numeric_limits<int64_t>::max()-delta) continue;
            result.insert(result.end(), {(int64_t)((start-first)/mChannels), (int64_t)((limit-start)/mChannels), span.pts+delta});
        }
        while (!mSpans.empty() && mSpans.front().end <= end) mSpans.pop_front();
        return result;
    }

private:
    struct Span { uint64_t first, end; int64_t pts; };
    static constexpr size_t MAX_SPANS = 512;
    const int mRate, mChannels;
    std::deque<Span> mSpans;
    int64_t duration(uint64_t samples) const {
        const uint64_t frames = samples/mChannels;
        return (int64_t)(frames/mRate)*1000000000LL + (int64_t)(frames%mRate)*1000000000LL/mRate;
    }
    bool contiguous(const Span &span, int64_t pts) const {
        if (span.pts == 0 && pts == 0) return true;
        if (span.pts <= 0 || pts <= 0) return false;
        const int64_t delta = duration(span.end-span.first);
        if (span.pts > std::numeric_limits<int64_t>::max()-delta) return false;
        const int64_t difference = pts-(span.pts+delta), tolerance = 1000000000LL/mRate;
        return difference >= -tolerance && difference <= tolerance;
    }
};
#endif
