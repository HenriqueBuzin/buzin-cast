package io.buzincast.fireos3probe;

import java.nio.charset.Charset;

/** Immutable receiver identity/configuration; changes require one complete restart. */
public final class ReceiverOptions {
    public final String name;
    public final int port;
    public ReceiverOptions(String name, int port) {
        if (!validName(name) || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid receiver configuration");
        this.name=name; this.port=port;
    }
    public static boolean validName(String name) {
        if (name == null || name.getBytes(Charset.forName("UTF-8")).length > 63) return false;
        boolean visible=false;
        for (int i=0;i<name.length();i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
            if (!Character.isWhitespace(name.charAt(i))) visible=true;
        }
        return visible;
    }
}
