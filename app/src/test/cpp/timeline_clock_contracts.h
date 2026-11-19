#pragma once

// Linked only into the modern unit executable. Production keeps libc's clock.
// Zero means pass through, including all other tests and their worker threads.
static std::atomic<int64_t> contractMonotonicNs{0};
extern "C" int __real_clock_gettime(clockid_t clock, struct timespec *value);
extern "C" int __wrap_clock_gettime(clockid_t clock, struct timespec *value) {
    const int64_t now = contractMonotonicNs.load(std::memory_order_relaxed);
    if (clock == CLOCK_MONOTONIC && now > 0) {
        value->tv_sec = static_cast<time_t>(now / 1000000000);
        value->tv_nsec = static_cast<long>(now % 1000000000);
        return 0;
    }
    return __real_clock_gettime(clock, value);
}

class ContractMonotonicClock {
public:
    ContractMonotonicClock() { CHECK(contractMonotonicNs.exchange(10000000000LL) == 0); }
    ~ContractMonotonicClock() { contractMonotonicNs.store(0); }
    void advance(int64_t nanoseconds) { contractMonotonicNs.fetch_add(nanoseconds); }
    ContractMonotonicClock(const ContractMonotonicClock &) = delete;
    ContractMonotonicClock &operator=(const ContractMonotonicClock &) = delete;
};

static void backlogTrimmingWaitsAfterAnUnderrun() {
    ContractMonotonicClock clock;
    TimelineBuffer timeline(1000, 1, 20, 95);
    std::array<int16_t, 100> input;
    input.fill(7);
    std::array<int16_t, 1000> output{};
    timeline.write(input.data(), input.size(), 0);
    CHECK(timeline.read(output.data(), 1).samples == 1);
    CHECK(timeline.debugInfo().metrics.trims == 0);

    clock.advance(1100000000);
    CHECK(timeline.read(output.data(), output.size()).samples == 99);
    for (size_t i = 0; i < output.size(); ++i) CHECK(output[i] == (i < 99 ? 7 : 0));
    CHECK(timeline.debugInfo().metrics.underruns == 1);
    timeline.write(input.data(), input.size(), 0);

    // Backlog has exceeded the cap for over two seconds, but the underrun
    // happened only 1.1 seconds ago: preserve the rebuilding cushion.
    clock.advance(1100000000);
    CHECK(timeline.read(output.data(), 1).samples == 1 && output[0] == 7);
    CHECK(timeline.debugInfo().metrics.trims == 0);
    clock.advance(899999999);
    CHECK(timeline.read(output.data(), 1).samples == 1 && output[0] == 7);
    CHECK(timeline.debugInfo().metrics.trims == 0);

    // Exactly at the two-second throttle boundary, trim only excess backlog.
    clock.advance(1);
    const auto resumed = timeline.read(output.data(), 1);
    CHECK(resumed.samples == 1 && resumed.firstSample == 180 && output[0] == 7);
    const auto debug = timeline.debugInfo();
    CHECK(debug.metrics.trims == 1 && debug.metrics.underruns == 1 && debug.backlogMs == 19);
}
