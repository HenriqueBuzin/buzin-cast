package io.buzincast.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Locale;
import io.buzincast.fireos3probe.ReceiverOptions;

/** Same stable preference keys as the main app; UI labels are never persisted as keys. */
final class LegacyPreferences {
    private final SharedPreferences preferences;
    LegacyPreferences(Context context) { preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE); }
    String name() {
        String value=preferences.getString("server_name","Buzin Cast");
        return ReceiverOptions.validName(value)?value:"Buzin Cast";
    }
    int port() { int value=preferences.getInt("server_port",7000); return value>0 && value<=65535?value:7000; }
    boolean autoStart() { return preferences.getBoolean("auto_start",true); }
    boolean keepScreenOn() { return preferences.getBoolean("keep_screen_on",true); }
    boolean advanced() { return preferences.getBoolean("developer_options",false); }
    void name(String value) { new ReceiverOptions(value,port()); preferences.edit().putString("server_name",value).apply(); }
    void port(int value) { new ReceiverOptions(name(),value); preferences.edit().putInt("server_port",value).apply(); }
    void bool(String key,boolean value) { preferences.edit().putBoolean(key,value).apply(); }
    ReceiverOptions options() { return new ReceiverOptions(name(),port()); }
    String identity() {
        String stored=preferences.getString("fallback_mac_address",null);
        if (stored!=null && stored.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) return stored;
        byte[] value=new byte[6]; new SecureRandom().nextBytes(value); value[0]=(byte)((value[0]&0xfc)|2);
        String result=String.format(Locale.US,"%02X:%02X:%02X:%02X:%02X:%02X",value[0]&255,value[1]&255,
                value[2]&255,value[3]&255,value[4]&255,value[5]&255);
        preferences.edit().putString("fallback_mac_address",result).apply(); return result;
    }
}
