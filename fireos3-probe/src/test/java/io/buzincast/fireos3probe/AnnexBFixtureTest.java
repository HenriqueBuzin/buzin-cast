package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class AnnexBFixtureTest {
    @Test public void convertsAvcLengthsAndRejectsTruncation() throws Exception {
        assertArrayEquals(new byte[]{0,0,0,1,0x67,0x42,0,0,0,1,0x68},
                StreamingSelfTest.toAnnexB(new byte[]{0,0,0,2,0x67,0x42,0,0,0,1,0x68}));
        assertThrows(java.io.IOException.class, () -> StreamingSelfTest.toAnnexB(new byte[]{0,0,0}));
        assertThrows(java.io.IOException.class, () -> StreamingSelfTest.toAnnexB(new byte[]{0,0,2,0,0x67}));
    }
}
