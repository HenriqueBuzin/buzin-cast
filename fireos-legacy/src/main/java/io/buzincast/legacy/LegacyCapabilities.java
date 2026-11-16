package io.buzincast.legacy;

import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import android.os.Build;
import io.buzincast.core.*;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** New codec APIs stay in a guarded nested class, outside the API17 verifier path. */
final class LegacyCapabilities {
    static DeviceCapabilities read() {
        DeviceCapabilities documented=LegacyProfiles.documentedLegacyDevice(Build.MANUFACTURER,Build.MODEL);
        if(Build.VERSION.SDK_INT>=21) {
            DeviceCapabilities detected=Api21.read();
            if(!detected.getModes().isEmpty())return detected;
        }
        if(!documented.getModes().isEmpty())return documented;
        if(BuildConfig.DEBUG && (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")))
            return new DeviceCapabilities(Collections.singleton(LegacyProfiles.HD),false);
        return new DeviceCapabilities(Collections.emptySet(),false);
    }
    @android.annotation.TargetApi(21)
    private static final class Api21 {
        static DeviceCapabilities read() {
            Set<VideoMode> modes=new LinkedHashSet<>();boolean lowLatency=false;
            VideoMode[] candidates={LegacyProfiles.HD,new VideoMode(1280,720,60),LegacyProfiles.FULL_HD,new VideoMode(1920,1080,60)};
            try {
                for(MediaCodecInfo codec:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    if(codec.isEncoder())continue;
                    for(String type:codec.getSupportedTypes())if(type.equalsIgnoreCase("video/avc"))try {
                        MediaCodecInfo.CodecCapabilities caps=codec.getCapabilitiesForType(type);
                        MediaCodecInfo.VideoCapabilities video=caps.getVideoCapabilities();
                        if(video==null)continue;
                        for(VideoMode mode:candidates)if(video.areSizeAndRateSupported(mode.getWidth(),mode.getHeight(),mode.getFps()))modes.add(mode);
                        if(Build.VERSION.SDK_INT>=30 && caps.isFeatureSupported("low-latency"))lowLatency=true;
                    } catch(RuntimeException unsupported) { /* Continue to another AVC decoder. */ }
                }
            } catch(RuntimeException | LinkageError unavailable) { /* Documented legacy model is the fallback. */ }
            return new DeviceCapabilities(modes,lowLatency);
        }
    }
}
