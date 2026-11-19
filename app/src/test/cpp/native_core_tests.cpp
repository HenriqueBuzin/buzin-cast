#include "timeline_buffer.h"
#include "pcm_timeline_clock.h"
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
#include "log_sink_contracts.h"
#ifdef BUZIN_DNSSD_ALLOC_CONTRACTS
extern "C" int dnssdAllocationFailureStatus(void);
static void dnssdAllocationFailuresReleasePartialState() {
    const int status = dnssdAllocationFailureStatus();
    if (status) throw std::runtime_error("DNS-SD allocation contract failed at line " + std::to_string(status));
}
#endif
#ifdef BUZIN_CLOCK_CONTRACTS
#include "timeline_clock_contracts.h"
#endif
#ifdef BUZIN_CALLBACK_KEY_CONTRACTS
extern "C" int callbackThreadKeyCreationFailureStatus(void);
static void callbackThreadKeyCreationFailureBlocksAttachment() {
    const int status = callbackThreadKeyCreationFailureStatus();
    if (status) throw std::runtime_error("TLS key contract failed at line " + std::to_string(status));
}
#endif

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
    DelayTracker extreme(1000,1,0,95);
    extreme.observe(1000000000,1000000000,10000000);
    CHECK(extreme.target()==15);
    // A multi-second arrival spike saturates the histogram and cushion ceiling.
    extreme.observe(1010000000,4010000000,10000000);
    CHECK(extreme.ceil()==1000 && extreme.target()==extreme.ceil());
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

    // Unsynchronized packets must remain audible without entering timestamped
    // capture. Once the sender clock is ready, capture receives the original PCM.
    TimelineBuffer syncing(1000,1,0,95);
    int synchronizedCaptures=0;
    syncing.capturePcm=[&](const int16_t *pcm,size_t samples,int64_t pts) {
        CHECK(pcm==second.data() && samples==second.size() && pts==2000000000);
        ++synchronizedCaptures;
    };
    syncing.write(first.data(),0,0);
    syncing.write(first.data(),first.size(),0);
    CHECK(synchronizedCaptures==0);
    int16_t capturedOutput[10]{};
    CHECK(syncing.read(capturedOutput,10).samples==10);
    for(auto value:capturedOutput) CHECK(value==1);
    syncing.write(second.data(),second.size(),2000000000);
    CHECK(synchronizedCaptures==1);
    CHECK(syncing.read(capturedOutput,10).samples==10);
    for(auto value:capturedOutput) CHECK(value==2);
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
    PcmTimelineClock clock(1000,1);
    timeline.enqueuedPcm=[&](uint64_t first,size_t count,int64_t pts){clock.enqueued(first,count,pts);};
    timeline.write(input,100,1000000000); timeline.read(&output,1); CHECK(output==1);
    std::this_thread::sleep_for(std::chrono::milliseconds(2100)); auto read=timeline.read(&output,1);
    CHECK(output==1 && timeline.debugInfo().metrics.trims==1 && timeline.debugInfo().backlogMs==19);
    auto timing=clock.read(read.firstSample,read.samples);
    CHECK(read.firstSample==80 && timing.size()==3 && timing[2]==1080000000);
#ifdef BUZIN_CLOCK_CONTRACTS
    backlogTrimmingWaitsAfterAnUnderrun();
#endif
}
static void acceptedClockExcludesLatePacketsAndPadding() {
    TimelineBuffer timeline(1000,1,0,95); int16_t packet[10]{};
    struct Event { uint64_t first; size_t count; int64_t pts; };
    std::vector<Event> events;
    timeline.enqueuedPcm=[&](uint64_t first,size_t count,int64_t pts){events.push_back({first,count,pts});};
    timeline.write(packet,10,1000000000); timeline.write(packet,10,1030000000);
    timeline.write(packet,10,1010000000); // rejected overlap must have no accepted-clock event
    CHECK(events.size()==3 && events[1].first==10 && events[1].count==20 && events[1].pts==1010000000);
    int16_t output[50]{}; const auto read=timeline.read(output,50);
    CHECK(read.firstSample==0 && read.samples==40);
    CHECK(timeline.read(output,50).samples==0);
    TimelineBuffer priming(1000,1,20,95); priming.write(packet,10,1000000000);
    CHECK(priming.read(output,5).samples==0); // generated prebuffer silence is not timestamped PCM
}
static void clockKeepsDiscontinuitiesAndBoundsMetadata() {
    PcmTimelineClock clock(1000,2);
    const uint64_t base=(uint64_t{1}<<32)+100;
    clock.enqueued(base,20,1000000000); clock.enqueued(base+20,20,3000000000);
    auto timing=clock.read(base+10,30);
    CHECK(timing.size()==6 && timing[0]==0 && timing[1]==5 && timing[2]==1005000000);
    CHECK(timing[3]==5 && timing[4]==10 && timing[5]==3000000000);
    PcmTimelineClock unknown(1000,2); unknown.enqueued(0,20,0); CHECK(unknown.read(0,20).empty());
    PcmTimelineClock bounded(1000,1);
    for(int i=0;i<1000;++i) bounded.enqueued(i,1,1000000000LL+i*1000000000LL);
    auto recent=bounded.read(0,1000); CHECK(recent.size()==512*3 && recent[0]==488);
    PcmTimelineClock longStream(44100,2);
    const uint64_t frames=44100ULL*86400*10;
    const uint64_t hour=44100ULL*3600;
    for(int h=0;h<240;h++) longStream.enqueued(h*hour*2,(size_t)(hour*2),1000000000LL+h*3600000000000LL);
    auto late=longStream.read((frames-1)*2,2);
    CHECK(late.size()==3 && late[2]>864000000000000LL); // ten days, no multiply overflow
}
static void acceptedClockReportsActualRingCapacity() {
    TimelineBuffer timeline(1000,1,0,95);
    std::vector<int16_t> input(10000,1),output(10000);
    size_t accepted=0;
    timeline.enqueuedPcm=[&](uint64_t first,size_t count,int64_t){CHECK(first==0);accepted+=count;};
    timeline.write(input.data(),input.size(),1000000000);
    CHECK(accepted>0 && accepted<input.size());
    const size_t atCapacity=accepted;
    timeline.write(input.data(),input.size(),0);
    CHECK(accepted==atCapacity); // A full ring emits no accepted-clock metadata.
    CHECK(timeline.read(output.data(),10000).samples==accepted);
}
static void stereoResumesAfterUnderrunAndClockReset() {
    TimelineBuffer timeline(1000, 2, 1, 95);
    std::array<int16_t, 20> packet;
    std::array<int16_t, 30> output;
    packet.fill(1);
    timeline.write(packet.data(), packet.size(), 1'000'000'000);
    timeline.read(output.data(), 15);
    for (size_t i = 0; i < output.size(); ++i) CHECK(output[i] == (i < packet.size() ? 1 : 0));

    packet.fill(2);
    timeline.write(packet.data(), packet.size(), 1'040'000'000);
    timeline.read(output.data(), 10);
    for (size_t i = 0; i < packet.size(); ++i) CHECK(output[i] == 2);

    packet.fill(3);
    timeline.write(packet.data(), packet.size(), 100'000'000);
    timeline.read(output.data(), 10);
    for (size_t i = 0; i < packet.size(); ++i) CHECK(output[i] == 3);
    const auto metrics = timeline.debugInfo().metrics;
    CHECK(metrics.underruns == 1 && metrics.silences == 0 && metrics.drops == 0);
}
int main() {
    const std::pair<const char*,void(*)()> cases[]={
        {"ring bounds and wrap",ringBoundsAndWrap},{"64-bit stream positions",ringCrosses32BitBoundary},
        {"SPSC concurrent order",ringConcurrentOrder},{"jitter policies",fixedAndAdaptiveJitter},
        {"PCM underrun and resume",pcmUnderrunAndReprime},{"short sounds",partialSoundsEventuallyPlay},
        {"timestamp gaps drops and capture",timestampGapsDropsAndCapture},{"discontinuity and metrics",discontinuityAndMetrics},
        {"sustained backlog trimming",sustainedBacklogIsTrimmed},
        {"accepted PCM clock metadata",acceptedClockExcludesLatePacketsAndPadding},
        {"discontinuities and bounded clock metadata",clockKeepsDiscontinuitiesAndBoundsMetadata},
        {"ring overflow clock metadata",acceptedClockReportsActualRingCapacity},
        {"stereo recovery after underrun and clock reset",stereoResumesAfterUnderrunAndClockReset},
        {"JNI log formatting and forwarding",logSinkFormatsAndForwards},
        {"JNI VM failures and thread attachment",logSinkVmFailuresAndAttachment},
        {"JNI callback binding failures",logSinkBindingFailures},
        {"JNI string allocation and callback failures",logSinkAllocationAndCallbackFailures},
        {"JNI log rebinding reference ownership",logSinkRebindingReleasesReferences}
#ifdef BUZIN_DNSSD_ALLOC_CONTRACTS
        ,{"DNS-SD allocation failures release partial state",dnssdAllocationFailuresReleasePartialState}
#endif
#ifdef BUZIN_CALLBACK_KEY_CONTRACTS
        ,{"TLS key creation failure blocks JVM attachment",callbackThreadKeyCreationFailureBlocksAttachment}
#endif
    };
    int failures=0;
    for(const auto &test:cases) { try {test.second(); std::cout<<"PASS "<<test.first<<std::endl;} catch(const std::exception &error) {++failures; std::cerr<<"FAIL "<<test.first<<": "<<error.what()<<std::endl;} }
    std::cout<<"Native tests: "<<std::size(cases)<<", failures: "<<failures<<std::endl;
    return failures ? 1 : 0;
}
