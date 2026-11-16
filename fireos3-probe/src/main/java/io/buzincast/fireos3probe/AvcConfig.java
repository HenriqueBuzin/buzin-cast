package io.buzincast.fireos3probe;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Bounded Annex-B SPS/PPS extraction and H.264 coded/cropped dimensions. */
final class AvcConfig {
    final byte[] sps, pps;
    final int width, height;

    private AvcConfig(byte[] sps, byte[] pps) {
        if ((sps[0] & 0x80) != 0 || (pps[0] & 0x80) != 0) throw new IllegalArgumentException("Invalid H.264 NAL header");
        this.sps = sps; this.pps = pps;
        Bits bits = new Bits(unescape(sps));
        bits.read(8); // NAL header
        int profile = bits.read(8);
        bits.read(8); bits.read(8); bits.ue();
        int chroma = 1, separate = 0;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 ||
            profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chroma = bits.ue();
            if (chroma > 3) throw new IllegalArgumentException("Invalid H.264 chroma format");
            if (chroma == 3) separate = bits.read(1);
            bits.ue(); bits.ue(); bits.read(1);
            if (bits.read(1) != 0) {
                for (int i = 0; i < (chroma == 3 ? 12 : 8); i++) {
                    if (bits.read(1) != 0) skipScaling(bits, i < 6 ? 16 : 64);
                }
            }
        }
        bits.ue();
        int order = bits.ue();
        if (order == 0) bits.ue();
        else if (order == 1) {
            bits.read(1); bits.se(); bits.se();
            int count = bits.ue();
            if (count > 255) throw new IllegalArgumentException("Invalid H.264 order cycle");
            for (int i = 0; i < count; i++) bits.se();
        } else if (order != 2) throw new IllegalArgumentException("Invalid H.264 picture order");
        bits.ue(); bits.read(1);
        int columns = bits.ue(), rows = bits.ue();
        if (columns > 119 || rows > 119) throw new IllegalArgumentException("H.264 dimensions exceed the probe budget");
        int frameOnly = bits.read(1);
        long codedWidth = (columns+1L)*16, codedHeight = (rows+1L)*16*(2-frameOnly);
        if (codedWidth > 1920 || codedHeight > 1920 || codedWidth*codedHeight > 1920L*1088) {
            throw new IllegalArgumentException("H.264 coded dimensions exceed the probe budget");
        }
        if (frameOnly == 0) bits.read(1);
        bits.read(1);
        int left = 0, right = 0, top = 0, bottom = 0;
        if (bits.read(1) != 0) { left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue(); }
        int format = separate != 0 ? 0 : chroma;
        int cropX = format == 0 || format == 3 ? 1 : 2;
        int cropY = (format == 1 ? 2 : 1) * (2-frameOnly);
        long w = codedWidth - ((long)left+right)*cropX;
        long h = codedHeight - ((long)top+bottom)*cropY;
        if (w < 1 || h < 1 || w > 1920 || h > 1920 || w*h > 1920L*1080) {
            throw new IllegalArgumentException("H.264 dimensions exceed the probe budget");
        }
        width = (int)w; height = (int)h;
    }

    static AvcConfig fromAnnexB(byte[] data) {
        if (data.length > VideoFrameQueue.MAX_BYTES) throw new IllegalArgumentException("Oversized H.264 packet");
        byte[] sps = null, pps = null;
        int start = startCode(data, 0);
        while (start >= 0) {
            int content = start + (data[start+2] == 1 ? 3 : 4);
            int next = startCode(data, content);
            int end = next < 0 ? data.length : next;
            if (content < end) {
                int type = data[content] & 31;
                if (type == 7 || type == 8) {
                    if (end-content > 4096) throw new IllegalArgumentException("Oversized H.264 parameter set");
                    byte[] nal = Arrays.copyOfRange(data, content, end);
                    if (type == 7) sps = nal; else pps = nal;
                }
            }
            start = next;
        }
        return sps == null || pps == null ? null : new AvcConfig(sps, pps);
    }

    byte[] spsWithStartCode() { return prefix(sps); }
    byte[] ppsWithStartCode() { return prefix(pps); }
    boolean matches(AvcConfig other) { return Arrays.equals(sps, other.sps) && Arrays.equals(pps, other.pps); }
    private static byte[] prefix(byte[] nal) {
        byte[] result = new byte[nal.length+4]; result[3] = 1;
        System.arraycopy(nal,0,result,4,nal.length); return result;
    }
    private static int startCode(byte[] data, int offset) {
        for (int i = offset; i+2 < data.length; i++) {
            if (data[i] == 0 && data[i+1] == 0 && (data[i+2] == 1 || (i+3 < data.length && data[i+2] == 0 && data[i+3] == 1))) return i;
        }
        return -1;
    }
    private static byte[] unescape(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i=0; i<data.length; i++) {
            if (i >= 2 && data[i] == 3 && data[i-1] == 0 && data[i-2] == 0) continue;
            out.write(data[i]);
        }
        return out.toByteArray();
    }
    private static void skipScaling(Bits bits, int size) {
        int last = 8, next = 8;
        for (int i=0; i<size; i++) {
            if (next != 0) next = (last + bits.se() + 256) & 255;
            if (next != 0) last = next;
        }
    }
    private static final class Bits {
        final byte[] data; int position;
        Bits(byte[] data) { this.data = data; }
        int read(int count) {
            if (position+count > data.length*8) throw new IllegalArgumentException("Truncated H.264 parameter set");
            int value = 0;
            while (count-- > 0) { value = (value<<1) | ((data[position/8] >> (7-position%8)) & 1); position++; }
            return value;
        }
        int ue() {
            int zeros=0;
            while (read(1) == 0) if (++zeros > 24) throw new IllegalArgumentException("Invalid H.264 Exp-Golomb value");
            return (1<<zeros)-1+read(zeros);
        }
        int se() { int code=ue(); return (code&1) == 0 ? -(code/2) : (code+1)/2; }
    }
}
