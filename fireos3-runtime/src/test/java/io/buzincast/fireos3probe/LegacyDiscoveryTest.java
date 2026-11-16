package io.buzincast.fireos3probe;

import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyDiscoveryTest {
    @Test public void doesNotAdvertiseAnAudioServiceForVideoOnlyCapabilities() {
        assertFalse(LegacyDiscovery.advertisesAudio(LegacyDiscovery.properties(new String[]{"features","0x80,0x0"})));
        assertTrue(LegacyDiscovery.advertisesAudio(LegacyDiscovery.properties(new String[]{"features","0x280,0x0"})));
        assertFalse(LegacyDiscovery.advertisesAudio(java.util.Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> LegacyDiscovery.advertisesAudio(LegacyDiscovery.properties(new String[]{"features","invalid"})));
    }
    @Test public void preservesNativePublicKeyAndCapabilities() {
        java.util.Map<String,String> records = LegacyDiscovery.properties(new String[]{"pk","public","features","0x80,0x0"});
        assertEquals("public",records.get("pk"));assertEquals("0x80,0x0",records.get("features"));
    }
    @Test public void rejectsBrokenNativePairs() {
        assertThrows(IllegalArgumentException.class, () -> LegacyDiscovery.properties(null));
        assertThrows(IllegalArgumentException.class, () -> LegacyDiscovery.properties(new String[]{"one"}));
        assertThrows(IllegalArgumentException.class, () -> LegacyDiscovery.properties(new String[]{"pk","a","pk","b"}));
        assertThrows(IllegalArgumentException.class, () -> LegacyDiscovery.properties(new String[]{"pk",null}));
    }
}
