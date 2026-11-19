package io.buzincast.fireos3probe;

/** Native HTTP workers read snapshots and wait here; they never access the player thread. */
final class LegacyNativePlayback {
    interface Listener {
        void play(long token,LegacyUrlRequest request);
        void seek(long token,float seconds);
        void rate(long token,float rate);
        void stop(long token);
    }
    static final class State {
        final long token;
        final double position,duration;
        final float rate;
        final boolean ready,buffering;
        State(long token,double position,double duration,float rate,boolean ready,boolean buffering) {
            this.token=token;this.position=position;this.duration=duration;this.rate=rate;this.ready=ready;this.buffering=buffering;
        }
        double[] values() {return new double[]{position,duration,rate,ready?1:0,buffering?1:0};}
    }
    private volatile State state=new State(0,0,-1,0,true,false);
    private volatile Listener listener;
    private boolean closed;
    synchronized void listener(Listener listener) {if(!closed)this.listener=listener;}
    boolean enabled() {return listener!=null;}
    State state() {return state;}
    void play(String location,float seconds) {
        if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>LegacyUrlRequest.MAX_POSITION_MS)throw new IllegalArgumentException("Invalid media position");
        LegacyUrlRequest request=new LegacyUrlRequest(location,(long)(seconds*1000.0),null);
        Listener target;long token;
        synchronized(this) {
            if(closed)return;
            target=listener;token=state.token+1;
            state=new State(token,seconds,target==null?-1:0,0,target==null,target!=null);notifyAll();
        }
        if(target!=null)try {target.play(token,request);}catch(RuntimeException error) {finish(token);throw error;}
    }
    void seek(float seconds) {
        if(Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds<0 || seconds*1000.0>LegacyUrlRequest.MAX_POSITION_MS)return;
        Listener target=listener;State current=state;if(target!=null && current.duration>=0)target.seek(current.token,seconds);
    }
    void rate(float rate) {
        if(Float.isNaN(rate) || Float.isInfinite(rate) || rate>4 || rate>0 && rate<.25f)return;
        Listener target=listener;State current=state;if(target!=null && current.duration>=0)target.rate(current.token,Math.max(0,rate));
    }
    void stop() {
        Listener target=listener;State current=state;
        finish(current.token);if(target!=null && current.duration>=0)target.stop(current.token);
    }
    synchronized boolean update(long token,double position,double duration,float rate,boolean ready,boolean buffering) {
        if(closed || token!=state.token || state.duration<0 || Double.isNaN(position) || Double.isInfinite(position) || Double.isNaN(duration) || Double.isInfinite(duration) || Float.isNaN(rate) || Float.isInfinite(rate) ||
            position<0 || duration<0 || rate<0 || rate>4)return false;
        state=new State(token,position,duration,rate,ready,buffering);notifyAll();return true;
    }
    synchronized void finish(long token) {
        if(token!=state.token)return;
        state=new State(token,0,-1,0,true,false);notifyAll();
    }
    void awaitReady(long timeoutMs) throws InterruptedException {
        long end=System.nanoTime()+timeoutMs*1000000L;
        synchronized(this) {
            long token=state.token;
            while(!closed && state.token==token && !state.ready) {
                long remaining=end-System.nanoTime();if(remaining<=0)return;
                wait(Math.max(1,remaining/1000000L));
            }
        }
    }
    synchronized void close() {closed=true;listener=null;finish(state.token);notifyAll();}
}
