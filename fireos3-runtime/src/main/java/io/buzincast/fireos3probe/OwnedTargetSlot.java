package io.buzincast.fireos3probe;

/** Producer requests changes; only the decoder consumer retires its active target. */
final class OwnedTargetSlot<T> {
    static final class Target<T> {
        final T value;
        private final Runnable release;
        private boolean released;
        Target(T value,Runnable release) { this.value=value;this.release=release; }
        void close() { if(!released) {released=true;release.run();} }
    }
    private Target<T> requested,active;
    private boolean closed;
    synchronized void request(T value,Runnable release) {
        if(value==null && release!=null)throw new IllegalArgumentException("Detached target cannot own resources");
        if(value!=null && release==null)throw new IllegalArgumentException("Owned target needs a release action");
        Target<T> next=value==null?null:new Target<>(value,release);
        if(closed) {if(next!=null)next.close();return;}
        Target<T> previous=requested;requested=next;
        if(previous!=null && previous!=active)previous.close();
    }
    synchronized boolean changed() { return active!=requested; }
    // Call only after releasing the codec which used the previous active target.
    synchronized Target<T> activate() {
        Target<T> previous=active;active=requested;
        if(previous!=null && previous!=active)previous.close();
        return active;
    }
    // Call after the decoder worker stopped, including startup failure before run().
    synchronized void close() {
        closed=true;
        Target<T> previous=active,pending=requested;
        active=requested=null;
        try {if(previous!=null)previous.close();}
        finally {if(pending!=null && pending!=previous)pending.close();}
    }
}
