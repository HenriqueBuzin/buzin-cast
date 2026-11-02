#include "timeline_buffer.h"
#include <array>
#include <chrono>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>
#include <vector>

// Only the counter seed is exposed to tests: simulate many hours on 32-bit TVs.
struct SpscRingTestAccess {
    static void seed(SpscRing &ring, uint64_t value) { ring.mRead.store(value); ring.mWrite.store(value); }
    static auto position(const SpscRing &ring) { return ring.mWrite.load(); }
};
static_assert(std::numeric_limits<decltype(SpscRingTestAccess::position(std::declval<SpscRing>()))>::digits >= 64,
              "Audio positions must remain 64-bit on ARM32 as well as ARM64");
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(std::string(#condition)+" at line "+std::to_string(__LINE__)); } while(false)

static void ringBoundsAndWrap() {
    SpscRing ring(7);
    const int16_t input[] = {1,2,3,4,5,6,7,8,9}; int16_t output[10]{};
    CHECK(ring.available()==0); CHECK(ring.write(input,9)==7); CHECK(ring.write(input,1)==0);
    CHECK(ring.read(output,5)==5); for(int i=0;i<5;++i) CHECK(output[i]==i+1);
    CHECK(ring.write(input,5)==5); CHECK(ring.read(output,9)==7);
    CHECK(output[0]==6 && output[1]==7); for(int i=0;i<5;++i) CHECK(output[i+2]==i+1);
    ring.write(input,4); ring.skip(2); CHECK(ring.available()==2); ring.skip(99); CHECK(ring.available()==0);
}
static void ringCrosses32BitBoundary() {
    SpscRing ring(7); const uint64_t base=(uint64_t{1}<<32)-3;
    SpscRingTestAccess::seed(ring,base);
    const int16_t input[]={2,4,6,8,10}; int16_t output[5]{};
    CHECK(ring.write(input,5)==5); CHECK(SpscRingTestAccess::position(ring)==base+5);
    CHECK(ring.read(output,5)==5); for(int i=0;i<5;++i) CHECK(output[i]==input[i]);
    CHECK(ring.available()==0);
}
static void ringConcurrentOrder() {
    SpscRing ring(61); constexpr size_t total=100000;
    std::thread writer([&]{ for(size_t i=0;i<total;) { int16_t value=i%30000; if(ring.write(&value,1)) ++i; else std::this_thread::yield(); } });
    bool ordered=true;
    for(size_t i=0;i<total;) { int16_t value{}; if(ring.read(&value,1)) { if(value!=static_cast<int16_t>(i%30000)) ordered=false; ++i; } else std::this_thread::yield(); }
    writer.join(); CHECK(ordered); CHECK(ring.available()==0);
}
static void fixedAndAdaptiveJitter() {
    DelayTracker fixed(48000,2,20,95); CHECK(fixed.target()==1920 && fixed.ceil()==1920);
    fixed.noteOutputBufferFrames(4096); fixed.observe(1,900000000,10000000); fixed.reanchor(); CHECK(fixed.target()==1920);
    DelayTracker adaptive(48000,2,0,95); adaptive.noteOutputBufferFrames(480);
    adaptive.observe(1000000000,1010000000,20000000); const auto initial=adaptive.target(); CHECK(initial>0);
    for(int i=0;i<600;++i) adaptive.observe(1000000000LL+i*20000000LL,1800000000LL+i*20000000LL,20000000);
    CHECK(adaptive.target()>initial && adaptive.target()<=adaptive.ceil());
    adaptive.reanchor(); adaptive.observe(100,200,10000000); CHECK(adaptive.target()<=adaptive.ceil());
}
static void pcmUnderrunAndReprime() {
    TimelineBuffer timeline(1000,1,0,95); int16_t input[]={1,2,3,4}, output[4]{};
    timeline.write(input,0,0); timeline.write(input,4,0); timeline.read(output,4);
    for(int i=0;i<4;++i) CHECK(output[i]==input[i]);
    timeline.read(output,4); for(auto value:output) CHECK(value==0); CHECK(timeline.debugInfo().metrics.underruns==1);
    timeline.write(input,4,0); timeline.flushAndReprime(); timeline.read(output,4); for(auto value:output) CHECK(value==0);
    timeline.write(input,4,0); timeline.reprime(); timeline.read(output,4); CHECK(output[0]==1 && output[3]==4);
}
static void partialSoundsEventuallyPlay() {
    TimelineBuffer timeline(1000,1,10,95); int16_t input[]={1,2,3,4}; int16_t output[5]{};
    timeline.write(input,4,0);
    for(int i=0;i<3;++i) { timeline.read(output,5); for(auto value:output) CHECK(value==0); }
    timeline.read(output,5); CHECK(output[0]==1 && output[3]==4 && output[4]==0);
}
static void timestampGapsDropsAndCapture() {
    TimelineBuffer timeline(1000,1,0,95); std::array<int16_t,10> first{},second{}; first.fill(1); second.fill(2);
    int captures=0; timeline.capturePcm=[&](const int16_t*,size_t samples,int64_t pts){ CHECK(samples==10 && pts>0); ++captures; };
    timeline.write(first.data(),10,1000000000); timeline.write(second.data(),10,1030000000);
    timeline.write(first.data(),10,1010000000); CHECK(captures==3);
    int16_t output[40]{}; timeline.read(output,40);
    for(int i=0;i<10;++i) CHECK(output[i]==1); for(int i=10;i<30;++i) CHECK(output[i]==0); for(int i=30;i<40;++i) CHECK(output[i]==2);
    auto debug=timeline.debugInfo(); CHECK(debug.metrics.silences==1 && debug.metrics.drops==1);
}
static void discontinuityAndMetrics() {
    TimelineBuffer timeline(1000,1,0,95); int16_t data[10]{};
    timeline.noteOutputBufferFrames(1); timeline.write(data,10,1000000000); timeline.write(data,10,3000000000); timeline.reanchorTracker();
    CHECK(timeline.debugInfo().metrics.silences==0);
    TimelineMetrics metrics; std::vector<std::thread> threads;
    for(int t=0;t<4;++t) threads.emplace_back([&]{ for(int i=0;i<10000;++i) {metrics.countTrim(); metrics.countDrop(); metrics.countSilence(); metrics.countUnderrun();} });
    for(auto &thread:threads) thread.join(); const auto debug=metrics.debugInfo();
    CHECK(debug.trims==40000 && debug.drops==40000 && debug.silences==40000 && debug.underruns==40000);
    const auto before=monoNs(); CHECK(before>0 && monoNs()>=before);
}
static void sustainedBacklogIsTrimmed() {
    TimelineBuffer timeline(1000,1,20,95); int16_t input[100]; std::fill_n(input,100,1); int16_t output{};
    timeline.write(input,100,0); timeline.read(&output,1); CHECK(output==1);
    std::this_thread::sleep_for(std::chrono::milliseconds(2100)); timeline.read(&output,1);
    CHECK(output==1 && timeline.debugInfo().metrics.trims==1 && timeline.debugInfo().backlogMs==19);
}
int main() {
    const std::pair<const char*,void(*)()> cases[]={
        {"ring bounds and wrap",ringBoundsAndWrap},{"64-bit stream positions",ringCrosses32BitBoundary},
        {"SPSC concurrent order",ringConcurrentOrder},{"jitter policies",fixedAndAdaptiveJitter},
        {"PCM underrun and resume",pcmUnderrunAndReprime},{"short sounds",partialSoundsEventuallyPlay},
        {"timestamp gaps drops and capture",timestampGapsDropsAndCapture},{"discontinuity and metrics",discontinuityAndMetrics},
        {"sustained backlog trimming",sustainedBacklogIsTrimmed}};
    int failures=0;
    for(const auto &test:cases) { try {test.second(); std::cout<<"PASS "<<test.first<<std::endl;} catch(const std::exception &error) {++failures; std::cerr<<"FAIL "<<test.first<<": "<<error.what()<<std::endl;} }
    std::cout<<"Native tests: "<<std::size(cases)<<", failures: "<<failures<<std::endl;
    return failures ? 1 : 0;
}
