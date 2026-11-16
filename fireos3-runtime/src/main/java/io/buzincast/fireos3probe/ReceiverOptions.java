package io.buzincast.fireos3probe;

import java.nio.charset.Charset;

/** Immutable receiver identity/configuration; changes require one complete restart. */
public final class ReceiverOptions {
    public final String name;
    public final int port;
    public final int width,height,fps,cushionMs,percentile;
    public final boolean lowLatency;
    public ReceiverOptions(String name, int port) {
        this(name,port,1280,720,30,false,20,95);
    }
    public ReceiverOptions(String name,int port,int width,int height,int fps,boolean lowLatency,int cushionMs,int percentile) {
        if (!validName(name) || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid receiver configuration");
        if(width<16 || width>4096 || height<16 || height>4096 || fps<1 || fps>240 ||
                cushionMs<0 || cushionMs>1000 || percentile<1 || percentile>100)
            throw new IllegalArgumentException("Invalid media configuration");
        this.name=name; this.port=port;
        this.width=width;this.height=height;this.fps=fps;this.lowLatency=lowLatency;
        this.cushionMs=cushionMs;this.percentile=percentile;
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
