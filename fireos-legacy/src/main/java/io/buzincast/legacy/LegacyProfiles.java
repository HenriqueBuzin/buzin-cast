package io.buzincast.legacy;

import io.buzincast.core.*;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Same stable keys and QualityProfiles policy as the modern frontend. */
final class LegacyProfiles {
    static final String[] KEYS={"Padrão","Cinema","Apresentação","Jogos"};
    static final int[] PERCENTILES={80,85,90,95,99};
    static final VideoMode HD=new VideoMode(1280,720,30), FULL_HD=new VideoMode(1920,1080,30);
    static boolean valid(String key) { for(String value:KEYS)if(value.equals(key))return true;return false; }
    static DeviceCapabilities documentedLegacyDevice(String manufacturer,String model) {
        Set<VideoMode> modes=new LinkedHashSet<>();
        // Amazon's published AVC limits for the original physical Stick (AFTM).
        // https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html?v=ftvstickgen1
        if("Amazon".equalsIgnoreCase(manufacturer) && "AFTM".equals(model)) {
            modes.add(HD);modes.add(new VideoMode(1280,720,60));modes.add(FULL_HD);
        }
        return new DeviceCapabilities(modes,false);
    }
    static QualityPlan choose(String key,DeviceCapabilities capabilities) {
        if(!valid(key))throw new IllegalArgumentException("Unknown profile");
        if(key.equals(KEYS[0])) {
            VideoMode mode=capabilities.getModes().contains(FULL_HD)?FULL_HD:
                capabilities.getModes().isEmpty()?HD:QualityProfiles.INSTANCE.choose(ExperienceProfile.CINEMA,capabilities).getMode();
            return new QualityPlan(mode,false,true,40,3);
        }
        ExperienceProfile profile=key.equals(KEYS[1])?ExperienceProfile.CINEMA:
            key.equals(KEYS[2])?ExperienceProfile.PRESENTATION:ExperienceProfile.GAMES;
        return QualityProfiles.INSTANCE.choose(profile,capabilities);
    }
}
