package io.buzincast.fireos3probe;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class AnnexBNormalizationTest {
    @Test public void normalizesMixedPrefixesWithoutChangingPayloadOrInputPosition() throws Exception {
        byte[] data={9,0,0,1,0x67,4,0,0,3,1,0,0,0,1,0x68,5};
        ByteBuffer input=ByteBuffer.wrap(data);input.position(1);
        ByteBuffer normalized=AnnexBNormalization.normalize(input,64);byte[] actual=new byte[normalized.remaining()];normalized.get(actual);
        assertArrayEquals(new byte[]{0,0,0,1,0x67,4,0,0,3,1,0,0,0,1,0x68,5},actual);assertEquals(1,input.position());
        assertArrayEquals(actual,AnnexBNormalization.normalize(actual,64));
    }
    @Test public void rejectsMissingEmptyAndOversizedUnitsBeforeAllocatingOutput() {
        for(byte[] invalid:new byte[][]{{1,2,3,4},{0,0,1},{0,0,1,0,0,1,4},{2,0,0,1,4}})
            assertThrows(IOException.class,()->AnnexBNormalization.normalize(invalid,64));
        assertThrows(IOException.class,()->AnnexBNormalization.normalize(new byte[]{0,0,1,7,5},5));
    }
}
