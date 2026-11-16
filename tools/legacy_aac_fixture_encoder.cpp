// Host-only fixture generator. Link against FDK AAC v2.0.3 outside the APK.
// Usage: legacy_aac_fixture_encoder <480|512> <output.bin>
// Output: little-endian length + ASC, then length + raw AAC packets.
#include <aacenc_lib.h>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

static bool record(FILE *file, const unsigned char *data, unsigned size) {
    const unsigned char length[] = {static_cast<unsigned char>(size), static_cast<unsigned char>(size >> 8),
                                    static_cast<unsigned char>(size >> 16), static_cast<unsigned char>(size >> 24)};
    return fwrite(length, 1, 4, file) == 4 && fwrite(data, 1, size, file) == size;
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    int spf = std::atoi(argv[1]);
    if (spf != 480 && spf != 512) return 2;
    HANDLE_AACENCODER encoder = nullptr;
    if (aacEncOpen(&encoder, 0, 2) != AACENC_OK) return 3;
    const struct { AACENC_PARAM key; unsigned value; } params[] = {
        {AACENC_AOT, 39}, {AACENC_SAMPLERATE, 44100}, {AACENC_CHANNELMODE, MODE_2},
        {AACENC_SBR_MODE, 0}, {AACENC_GRANULE_LENGTH, static_cast<unsigned>(spf)},
        {AACENC_BITRATE, 128000}, {AACENC_TRANSMUX, TT_MP4_RAW}, {AACENC_AFTERBURNER, 0}};
    for (auto param : params) {
        if (aacEncoder_SetParam(encoder, param.key, param.value) != AACENC_OK) {
            aacEncClose(&encoder); return 4;
        }
    }
    AACENC_InfoStruct info{};
    if (aacEncEncode(encoder, nullptr, nullptr, nullptr, nullptr) != AACENC_OK ||
        aacEncInfo(encoder, &info) != AACENC_OK || info.frameLength != spf) {
        aacEncClose(&encoder); return 5;
    }
    FILE *file = std::fopen(argv[2], "wb");
    if (!file) { aacEncClose(&encoder); return 6; }
    bool ok = record(file, info.confBuf, info.confSize);
    std::vector<INT_PCM> pcm(spf * 2);
    unsigned char encoded[8192];
    for (int frame = 0; ok && frame < 12; ++frame) {
        for (int n = 0; n < spf; ++n) {
            const auto sample = static_cast<INT_PCM>(3000 * std::sin(2 * 3.141592653589793 * 440 * (frame*spf+n)/44100));
            pcm[n*2] = sample; pcm[n*2+1] = sample;
        }
        void *inData = pcm.data(), *outData = encoded;
        int inId = IN_AUDIO_DATA, outId = OUT_BITSTREAM_DATA;
        int inSize = static_cast<int>(pcm.size()*sizeof(INT_PCM)), outSize = sizeof(encoded);
        int inElement = sizeof(INT_PCM), outElement = 1;
        AACENC_BufDesc input{1, &inData, &inId, &inSize, &inElement};
        AACENC_BufDesc output{1, &outData, &outId, &outSize, &outElement};
        AACENC_InArgs inArgs{}; inArgs.numInSamples = static_cast<int>(pcm.size());
        AACENC_OutArgs outArgs{};
        ok = aacEncEncode(encoder, &input, &output, &inArgs, &outArgs) == AACENC_OK &&
             outArgs.numInSamples == static_cast<int>(pcm.size());
        if (ok && outArgs.numOutBytes > 0) ok = record(file, encoded, outArgs.numOutBytes);
    }
    if (fclose(file) != 0) ok = false;
    aacEncClose(&encoder);
    return ok ? 0 : 7;
}
