package io.buzincast.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Locale;
import io.buzincast.fireos3probe.ReceiverOptions;
import io.buzincast.core.*;

/** Same stable preference keys as the main app; UI labels are never persisted as keys. */
final class LegacyPreferences {
    private final SharedPreferences preferences;
    private final DeviceCapabilities capabilities;
    LegacyPreferences(Context context) {
        preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE);capabilities=LegacyCapabilities.read();
        if(!preferences.contains("resolution"))profile(LegacyProfiles.KEYS[0]);
    }
    String profile() {
        String key=preferences.getString("experience_profile",LegacyProfiles.KEYS[0]);return LegacyProfiles.valid(key)?key:LegacyProfiles.KEYS[0];
    }
    void profile(String key) {
        QualityPlan plan=LegacyProfiles.choose(key,capabilities);VideoMode mode=plan.getMode();
        preferences.edit().putString("experience_profile",key).putString("resolution",mode.getWidth()+"x"+mode.getHeight())
            .putInt("max_fps",mode.getFps()).putString("h265","off").putBoolean("low_latency",plan.getLowLatency())
            .putBoolean("audio_auto_buffer",plan.getAutoBuffer()).putInt("audio_cushion_ms",plan.getCushionMs())
            .putInt("audio_adaptive_step",plan.getAdaptiveStep()).putBoolean("sync_client_volume",true).apply();
    }
    String name() {
        String value=preferences.getString("server_name","Buzin Cast");
        return ReceiverOptions.validName(value)?value:"Buzin Cast";
    }
    int port() { int value=preferences.getInt("server_port",7000); return value>0 && value<=65535?value:7000; }
    boolean autoStart() { return preferences.getBoolean("auto_start",true); }
    boolean background() {return preferences.getBoolean("run_in_background",true);}
    boolean fcast() {return preferences.getBoolean("fcast_enabled",false);}
    boolean dlna() {return preferences.getBoolean("dlna_enabled",false);}
    boolean castV2() {return preferences.getBoolean("castv2_enabled",false);}
    boolean approveConnections() {return preferences.getBoolean("approve_connections",false);}
    boolean requirePin() {return preferences.getBoolean("require_pin",false);}
    boolean networkRecovery() {return preferences.getBoolean("network_recovery",true);}
    boolean bootAutoStart() {return preferences.getBoolean("boot_auto_start",false);}
    boolean serviceRequested() {return preferences.getBoolean("legacy_service_requested",false);}
    void serviceRequested(boolean value) {preferences.edit().putBoolean("legacy_service_requested",value).apply();}
    boolean keepScreenOn() { return preferences.getBoolean("keep_screen_on",true); }
    boolean advanced() { return preferences.getBoolean("developer_options",false); }
    int margin() { return Math.max(0,Math.min(10,preferences.getInt("tv_margin",2))); }
    void margin(int value) {
        if(value<0 || value>10)throw new IllegalArgumentException("Invalid TV margin");
        preferences.edit().putInt("tv_margin",value).apply();
    }
    void name(String value) { new ReceiverOptions(value,port()); preferences.edit().putString("server_name",value).apply(); }
    void port(int value) { new ReceiverOptions(name(),value); preferences.edit().putInt("server_port",value).apply(); }
    void bool(String key,boolean value) { preferences.edit().putBoolean(key,value).apply(); }
    ReceiverOptions options() {
        try {
            String[] size=preferences.getString("resolution","1280x720").split("x");
            int step=preferences.getInt("audio_adaptive_step",3);
            return new ReceiverOptions(name(),port(),Integer.parseInt(size[0]),Integer.parseInt(size[1]),preferences.getInt("max_fps",30),
                preferences.getBoolean("low_latency",false),preferences.getBoolean("audio_auto_buffer",true)?0:preferences.getInt("audio_cushion_ms",40),LegacyProfiles.PERCENTILES[step],requirePin());
        } catch(IllegalArgumentException | IndexOutOfBoundsException invalid) {
            profile(LegacyProfiles.KEYS[0]);return options();
        }
    }
    String identity() {
        String stored=preferences.getString("fallback_mac_address",null);
        if (stored!=null && stored.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) return stored;
        byte[] value=new byte[6]; new SecureRandom().nextBytes(value); value[0]=(byte)((value[0]&0xfc)|2);
        String result=String.format(Locale.US,"%02X:%02X:%02X:%02X:%02X:%02X",value[0]&255,value[1]&255,
                value[2]&255,value[3]&255,value[4]&255,value[5]&255);
        preferences.edit().putString("fallback_mac_address",result).apply(); return result;
    }
}
