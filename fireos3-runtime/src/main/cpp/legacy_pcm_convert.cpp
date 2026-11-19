#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <new>
#include <vector>
extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

namespace {
constexpr int maxFrames=16384,maxOutputFrames=262144;
struct Converter {
    SwrContext *swr=nullptr;
    int channels=0,bytesPerSample=0,outChannels=0,encoding=0;
    int mapping[8]{};
    bool draining=false;
    ~Converter() {swr_free(&swr);}
};
bool rate(int value) {return value>=1000 && value<=384000;}
int androidMask(int channels) {
    constexpr int masks[]={0,0x4,0xc,0x1c,0xcc,0xdc,0xfc,0x4fc,0x18fc};
    return channels>=1 && channels<=8?masks[channels]:0;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_buzincast_fireos3probe_LegacyPcmConverter_create(JNIEnv *env,jclass,jint inRate,jint channels,jint mask,jint encoding,jint outRate,jint outChannels,jintArray order) {
    if(!rate(inRate) || !rate(outRate) || channels<1 || channels>8 || outChannels<1 || outChannels>2)return 0;
    if(mask==0)mask=androidMask(channels);
    if(mask<=0 || (mask&~0x1ffc)!=0 || __builtin_popcount((unsigned)mask)!=channels)return 0;
    AVSampleFormat format;int bytes;
    switch(encoding) {
        case 2:format=AV_SAMPLE_FMT_S16;bytes=2;break;
        case 3:format=AV_SAMPLE_FMT_U8;bytes=1;break;
        case 4:format=AV_SAMPLE_FMT_FLT;bytes=4;break;
        case 0x20000000:format=AV_SAMPLE_FMT_S32;bytes=3;break;
        case 0x30000000:format=AV_SAMPLE_FMT_S32;bytes=4;break;
        default:return 0;
    }
    std::unique_ptr<Converter> converter(new(std::nothrow) Converter);
    if(!converter)return 0;
    if(order) {
        if(env->GetArrayLength(order)!=channels)return 0;
        env->GetIntArrayRegion(order,0,channels,converter->mapping);if(env->ExceptionCheck())return 0;
        unsigned seen=0;for(int i=0;i<channels;i++) {int value=converter->mapping[i];if(value<0 || value>=channels || (seen&(1u<<value)))return 0;seen|=1u<<value;}
    }
    AVChannelLayout input{},output{};
    // Android's output speaker-position bits start at bit 2; the following
    // eleven positions have the same order as AVChannel's native mask.
    int error=av_channel_layout_from_mask(&input,channels==1?AV_CH_LAYOUT_MONO:(uint64_t)(mask>>2));
    if(error>=0)error=av_channel_layout_from_mask(&output,outChannels==1?AV_CH_LAYOUT_MONO:AV_CH_LAYOUT_STEREO);
    if(error>=0)error=swr_alloc_set_opts2(&converter->swr,&output,AV_SAMPLE_FMT_S16,outRate,&input,format,inRate,0,nullptr);
    av_channel_layout_uninit(&input);av_channel_layout_uninit(&output);
    if(error<0 || !converter->swr || (order && swr_set_channel_mapping(converter->swr,converter->mapping)<0) ||
        av_opt_set_double(converter->swr,"lfe_mix_level",0.5,0)<0 ||
        av_opt_set_double(converter->swr,"rematrix_maxval",1.0,0)<0 ||
        av_opt_set_int(converter->swr,"filter_size",64,0)<0 || swr_init(converter->swr)<0)return 0;
    converter->channels=channels;converter->bytesPerSample=bytes;converter->outChannels=outChannels;converter->encoding=encoding;
    return reinterpret_cast<jlong>(converter.release());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_buzincast_fireos3probe_LegacyPcmConverter_convert(JNIEnv *env,jclass,jlong handle,jobject input,jint offset,jint frames,jboolean end) {
    auto *converter=reinterpret_cast<Converter *>(handle);
    if(!converter || frames<0 || frames>maxFrames || offset<0 || (end && frames!=0) || (!end && converter->draining))return nullptr;
    const uint8_t *data=nullptr;
    const int64_t bytes=(int64_t)frames*converter->channels*converter->bytesPerSample;
    if(frames>0) {
        const jlong capacity=input?env->GetDirectBufferCapacity(input):-1;
        const auto *base=input?static_cast<const uint8_t *>(env->GetDirectBufferAddress(input)):nullptr;
        if(!base || capacity<0 || offset>capacity || bytes>capacity-offset || (converter->bytesPerSample!=3 && offset%converter->bytesPerSample!=0))return nullptr;
        data=base+offset;
        if(converter->encoding==4)for(int i=0;i<frames*converter->channels;i++) {
            float sample;std::memcpy(&sample,data+i*sizeof(float),sizeof(sample));if(!std::isfinite(sample))return nullptr;
        }
    }
    int capacity=swr_get_out_samples(converter->swr,frames);
    if(capacity<0 || capacity>maxOutputFrames)return nullptr;
    try {
        std::vector<int32_t> expanded;
        if(converter->encoding==0x20000000) {
            expanded.resize((size_t)frames*converter->channels);
            for(size_t i=0;i<expanded.size();i++) {
                uint32_t value=((uint32_t)data[i*3]|(uint32_t)data[i*3+1]<<8|(uint32_t)data[i*3+2]<<16)<<8;
                std::memcpy(&expanded[i],&value,sizeof(value));
            }
            data=reinterpret_cast<const uint8_t *>(expanded.data());
        }
        std::vector<uint8_t> output((size_t)std::max(1,capacity)*converter->outChannels*2);
        uint8_t *destination=output.data();
        if(end)converter->draining=true;
        // A non-null zero-length input drains available samples without
        // flushing. Only the explicit end call may flush the filter tail.
        const uint8_t empty=0;const uint8_t *source=data?data:&empty;
        int count=swr_convert(converter->swr,&destination,capacity,end?nullptr:&source,frames);
        if(count<0 || count>capacity)return nullptr;
        jsize length=count*converter->outChannels*2;
        jbyteArray result=env->NewByteArray(length);
        if(result && length>0)env->SetByteArrayRegion(result,0,length,reinterpret_cast<const jbyte *>(output.data()));
        return env->ExceptionCheck()?nullptr:result;
    }catch(const std::bad_alloc &) {return nullptr;}
}

extern "C" JNIEXPORT void JNICALL
Java_io_buzincast_fireos3probe_LegacyPcmConverter_destroy(JNIEnv *,jclass,jlong handle) {
    delete reinterpret_cast<Converter *>(handle);
}
