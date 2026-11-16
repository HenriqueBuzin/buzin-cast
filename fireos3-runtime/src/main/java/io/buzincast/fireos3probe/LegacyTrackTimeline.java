package io.buzincast.fireos3probe;

/** RTP sample counters are unsigned 32-bit values, including at rollover. */
final class LegacyTrackTimeline {
    private long position,duration,anchor;
    private boolean playing,hasPosition;
    static boolean validProgress(long start,long current,long end) {
        return (start|current|end)>=0 && start<=0xffffffffL && current<=0xffffffffL && end<=0xffffffffL &&
            ((end-start)&0xffffffffL)*1000/44100>0;
    }
    void duration(long value,long now) {
        position=position(now);anchor=now;if(value>0)duration=value;
        if(duration>0)position=Math.min(position,duration);
    }
    boolean progress(long start,long current,long end,long now) {
        if(!validProgress(start,current,end))return false;
        long length=(end-start)&0xffffffffL;
        long offset=(current-start)&0xffffffffL;
        if(offset>length)offset=((start-current)&0xffffffffL)<((current-end)&0xffffffffL)?0:length;
        duration=length*1000/44100;position=offset*1000/44100;
        anchor=now;playing=hasPosition=true;return true;
    }
    void playing(boolean value,long now) {position=position(now);anchor=now;playing=value;}
    long position(long now) {
        long elapsed=playing && hasPosition && now>anchor?now-anchor:0;
        long result=position>Long.MAX_VALUE-elapsed?Long.MAX_VALUE:position+elapsed;
        return duration>0?Math.min(result,duration):result;
    }
    long duration() {return duration;}
    boolean playing() {return playing;}
    void clear(long now) {position=duration=0;anchor=now;playing=hasPosition=false;}
}
