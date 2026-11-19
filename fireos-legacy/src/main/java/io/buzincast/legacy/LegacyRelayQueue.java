package io.buzincast.legacy;

import io.buzincast.media.MirrorPacket;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/** Same nonblocking offer/timed poll contract, with an encoded-byte cap for small heaps. */
public final class LegacyRelayQueue {
    public static final int MAX_BYTES=8*1024*1024;
    private final int capacity;
    private final ArrayDeque<MirrorPacket> queue=new ArrayDeque<>();
    private int bytes;
    public LegacyRelayQueue(int capacity) {if(capacity<1 || capacity>120)throw new IllegalArgumentException("Invalid relay queue size");this.capacity=capacity;}
    private static int size(MirrorPacket packet) {
        if(packet instanceof MirrorPacket.Video)return ((MirrorPacket.Video)packet).getData().length;
        if(packet instanceof MirrorPacket.Audio)return ((MirrorPacket.Audio)packet).getData().length;
        return 1;
    }
    public synchronized boolean offer(MirrorPacket packet) {
        if(packet==null)throw new NullPointerException("Missing relay packet");
        int size=size(packet);
        if(queue.size()>=capacity || size>MAX_BYTES-bytes)return false;
        queue.addLast(packet);bytes+=size;notifyAll();return true;
    }
    public synchronized MirrorPacket poll(long timeout,TimeUnit unit) throws InterruptedException {
        long remaining=unit.toNanos(timeout),deadline=System.nanoTime()+remaining;
        while(queue.isEmpty() && remaining>0) {TimeUnit.NANOSECONDS.timedWait(this,remaining);remaining=deadline-System.nanoTime();}
        MirrorPacket packet=queue.pollFirst();if(packet!=null)bytes-=size(packet);return packet;
    }
    public synchronized void clear() {queue.clear();bytes=0;notifyAll();}
}
