package io.buzincast.legacy;

import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyReconnectTest {
    @Test public void unchangedOnlineNotificationsCannotEraseABackoffOrBudget() {
        LegacyReconnect gate=new LegacyReconnect();assertFalse(gate.canStart(100));
        assertTrue(gate.observe("adapter/address-a"));assertTrue(gate.canStart(100));
        assertEquals(1500,gate.failed(100));assertFalse(gate.observe("adapter/address-a"));
        assertFalse(gate.canStart(1599));assertTrue(gate.canStart(1600));
        assertEquals(3000,gate.failed(1600));assertEquals(4500,gate.failed(4600));
        assertEquals(-1,gate.failed(9100));assertFalse(gate.observe("adapter/address-a"));assertFalse(gate.canStart(100000));
        assertTrue(gate.observe("adapter/address-b"));assertTrue(gate.canStart(100000));
    }
    @Test public void noNetworkAndCancellationCannotStartAReceiverFromAnOldDeadline() {
        LegacyReconnect gate=new LegacyReconnect();gate.observe(null);assertTrue(gate.initialized());assertFalse(gate.online());
        gate.observe("online");gate.failed(0);gate.observe(null);assertFalse(gate.canStart(10000));
        gate.observe("online");assertTrue(gate.canStart(10000));gate.failed(10000);gate.clear();
        assertFalse(gate.initialized());assertFalse(gate.canStart(100000));
        gate.observe("online");assertTrue(gate.canStart(100000));
    }
    @Test public void explicitRetryOrAStableSessionCanResetAnExhaustedEpisode() {
        LegacyReconnect gate=new LegacyReconnect();gate.observe("online");
        for(int failure=0;failure<4;failure++)gate.failed(failure*10000);
        assertFalse(gate.canStart(100000));gate.resetRetries();assertTrue(gate.canStart(100000));
        assertEquals(1500,gate.failed(100000));
    }
}
