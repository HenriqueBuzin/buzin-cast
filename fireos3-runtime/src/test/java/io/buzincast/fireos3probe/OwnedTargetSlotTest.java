package io.buzincast.fireos3probe;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedTargetSlotTest {
    @Test public void replacementRetainsDecoderTargetUntilConsumerRetiresIt() {
        OwnedTargetSlot<String> slot=new OwnedTargetSlot<>();AtomicInteger old=new AtomicInteger(),next=new AtomicInteger();
        slot.request("old",old::incrementAndGet);assertEquals("old",slot.activate().value);
        slot.request("next",next::incrementAndGet);assertEquals(0,old.get());assertTrue(slot.changed());
        assertEquals("next",slot.activate().value);assertEquals(1,old.get());assertEquals(0,next.get());
        slot.close();slot.close();assertEquals(1,next.get());assertFalse(slot.changed());
    }
    @Test public void supersededPendingTargetAndLateOffersCannotLeak() {
        OwnedTargetSlot<String> slot=new OwnedTargetSlot<>();AtomicInteger releases=new AtomicInteger();
        slot.request("one",releases::incrementAndGet);slot.request("two",releases::incrementAndGet);
        assertEquals(1,releases.get());slot.request(null,null);assertEquals(2,releases.get());
        assertNull(slot.activate());slot.close();slot.request("late",releases::incrementAndGet);
        assertEquals(3,releases.get());assertNull(slot.activate());
    }
    @Test public void detachKeepsActiveLeaseWhileDiscardingPendingReplacement() {
        OwnedTargetSlot<String> slot=new OwnedTargetSlot<>();AtomicInteger active=new AtomicInteger(),pending=new AtomicInteger();
        slot.request("active",active::incrementAndGet);slot.activate();
        slot.request("pending",pending::incrementAndGet);slot.request(null,null);
        assertEquals(0,active.get());assertEquals(1,pending.get());assertTrue(slot.changed());
        assertNull(slot.activate());assertEquals(1,active.get());slot.close();assertEquals(1,active.get());
    }
}
