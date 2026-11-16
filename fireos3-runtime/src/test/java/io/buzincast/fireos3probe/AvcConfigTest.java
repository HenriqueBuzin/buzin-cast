package io.buzincast.fireos3probe;

import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class AvcConfigTest {
    @Test public void displayResumeRequiresAnIdrRatherThanOnlyParameterSetsOrDeltaVideo() {
        assertTrue(AvcConfig.containsIdr(hex("000000016701000001650203")));
        assertTrue(AvcConfig.containsIdr(hex("000001650203")));
        assertFalse(AvcConfig.containsIdr(hex("00000001610102030000000168cb83")));
        assertFalse(AvcConfig.containsIdr(hex("00000001e5010203")));
        assertFalse(AvcConfig.containsIdr(hex("00000001")));
        assertFalse(AvcConfig.containsIdr(new byte[0]));
    }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length()/2];
        for(int i=0;i<result.length;i++) result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return result;
    }
    @Test public void readsTheRealSampleIncludingEmulationPrevention() {
        AvcConfig config = AvcConfig.fromAnnexB(hex("000000016742c00ad9047b0110000003001000000300a0f12264800000000168cb83cb20"));
        assertNotNull(config); assertEquals(64,config.width); assertEquals(48,config.height);
        assertEquals(1,config.spsWithStartCode()[3]);
    }
    @Test public void permitsThreeByteStartCodes() {
        assertNotNull(AvcConfig.fromAnnexB(hex("0000016742c00ad9047b0110000003001000000300a0f122648000000168cb83cb20")));
    }
    @Test public void waitsForBothParameterSetsRatherThanConfiguringFromDeltaVideo() {
        assertNull(AvcConfig.fromAnnexB(hex("0000000161010203")));
        assertNull(AvcConfig.fromAnnexB(hex("000000016742c00ad9047b01")));
    }
    @Test public void parsesCropped1080AndHighProfile() throws Exception {
        AvcConfig baseline = AvcConfig.fromAnnexB(parameterSets(66,120,68,4));
        assertEquals(1920,baseline.width); assertEquals(1080,baseline.height);
        AvcConfig high = AvcConfig.fromAnnexB(parameterSets(100,80,45,0));
        assertEquals(1280,high.width); assertEquals(720,high.height);
        assertTrue(high.matches(AvcConfig.fromAnnexB(parameterSets(100,80,45,0))));
        assertFalse(high.matches(baseline));
    }
    @Test public void rejectsTruncatedAndOversizedConfigurations() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(hex("00000001670000000168")));
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(parameterSets(66,121,68,0)));
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(parameterSets(66,120,120,500)));
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(parameterSets(66,2,2,100)));
    }
    @Test public void rejectsForbiddenHeaderAndHugeParameterSet() {
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(hex("00000001e742c00ad9047b010000000168cb83cb20")));
        byte[] oversized = new byte[5000]; oversized[3]=1;oversized[4]=0x67;
        assertThrows(IllegalArgumentException.class, () -> AvcConfig.fromAnnexB(oversized));
    }
    private static byte[] parameterSets(int profile,int columns,int rows,int bottomCrop) throws Exception {
        StringBuilder bits = new StringBuilder();
        append(bits,0x67,8);append(bits,profile,8);append(bits,0,8);append(bits,30,8);ue(bits,0);
        if(profile == 100) {ue(bits,1);ue(bits,0);ue(bits,0);append(bits,0,1);append(bits,0,1);}
        ue(bits,0);ue(bits,0);ue(bits,0);ue(bits,1);append(bits,0,1);
        ue(bits,columns-1);ue(bits,rows-1);append(bits,1,1);append(bits,1,1);append(bits,1,1);
        ue(bits,0);ue(bits,0);ue(bits,0);ue(bits,bottomCrop);append(bits,0,1);append(bits,1,1);
        while(bits.length()%8!=0) bits.append('0');
        ByteArrayOutputStream out = new ByteArrayOutputStream();out.write(new byte[]{0,0,0,1});
        for(int i=0;i<bits.length();i+=8)out.write(Integer.parseInt(bits.substring(i,i+8),2));
        out.write(hex("0000000168cb83cb20"));return out.toByteArray();
    }
    private static void append(StringBuilder out,int value,int count) {
        for(int i=count-1;i>=0;i--)out.append((value>>i)&1);
    }
    private static void ue(StringBuilder out,int value) {
        String code=Integer.toBinaryString(value+1);
        for(int i=1;i<code.length();i++)out.append('0');out.append(code);
    }
}
