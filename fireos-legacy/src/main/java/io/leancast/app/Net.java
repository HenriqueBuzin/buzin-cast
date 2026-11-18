package io.leancast.app;

import io.buzincast.fireos3probe.LegacyNetwork;

/** Platform binding for the shared FCast transport. Uses the native receiver's LAN selector. */
public final class Net {
    private Net() {}
    public static String lanIpv4() {
        try {return LegacyNetwork.select().address.getHostAddress();}
        catch(java.io.IOException | RuntimeException unavailable) {return null;}
    }
}
