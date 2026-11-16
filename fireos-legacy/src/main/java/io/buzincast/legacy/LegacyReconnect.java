package io.buzincast.legacy;

/** A bounded retry episode for one observed local network, with monotonic deadlines. */
final class LegacyReconnect {
    private boolean initialized,exhausted;
    private String key;
    private int attempts;
    private long retryAt;
    boolean observe(String value) {
        if(initialized && (key==null?value==null:key.equals(value)))return false;
        initialized=true;key=value;resetRetries();return true;
    }
    boolean initialized() {return initialized;}
    boolean online() {return initialized && key!=null;}
    boolean canStart(long now) {return online() && !exhausted && now>=retryAt;}
    long failed(long now) {
        if(attempts>=3) {exhausted=true;return -1;}
        long delay=++attempts*1500L;retryAt=now+delay;return delay;
    }
    void resetRetries() {attempts=0;retryAt=0;exhausted=false;}
    void clear() {initialized=false;key=null;resetRetries();}
}
