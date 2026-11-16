package io.buzincast.legacy;

import io.buzincast.core.*;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyProfilesTest {
    @Test public void originalStickUsesDocumentedModesWithoutInventing1080p60() {
        DeviceCapabilities caps=LegacyProfiles.documentedLegacyDevice("Amazon","AFTM");
        assertEquals(3,caps.getModes().size());
        assertEquals(new VideoMode(1920,1080,30),LegacyProfiles.choose("Cinema",caps).getMode());
        assertEquals(new VideoMode(1920,1080,30),LegacyProfiles.choose("Apresentação",caps).getMode());
        QualityPlan games=LegacyProfiles.choose("Jogos",caps);
        assertEquals(new VideoMode(1280,720,60),games.getMode());assertFalse(games.getLowLatency());
        assertFalse(games.getAutoBuffer());assertEquals(25,games.getCushionMs());
        assertEquals(4,LegacyProfiles.choose("Cinema",caps).getAdaptiveStep());
    }
    @Test public void unknownModelIsNotTreatedAsAStickAndPresetCannotInventModes() {
        DeviceCapabilities unknown=LegacyProfiles.documentedLegacyDevice("Other","AFTM");
        assertTrue(unknown.getModes().isEmpty());
        assertThrows(IllegalStateException.class,()->LegacyProfiles.choose("Jogos",unknown));
        assertThrows(IllegalArgumentException.class,()->LegacyProfiles.choose("Cinema translated",unknown));
    }
    @Test public void constrainedDeviceAndDefaultKeepSharedPolicy() {
        DeviceCapabilities caps=new DeviceCapabilities(Collections.singleton(new VideoMode(640,480,24)),false);
        assertEquals(new VideoMode(640,480,24),LegacyProfiles.choose("Jogos",caps).getMode());
        QualityPlan reset=LegacyProfiles.choose("Padrão",caps);
        assertTrue(reset.getAutoBuffer());assertFalse(reset.getLowLatency());
        assertEquals(40,reset.getCushionMs());assertEquals(3,reset.getAdaptiveStep());
    }
}
