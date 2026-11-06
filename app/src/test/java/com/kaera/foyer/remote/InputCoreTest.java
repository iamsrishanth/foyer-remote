package com.kaera.foyer.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Deterministic tests for the routing decisions that are too fast or too timing-dependent to force
 * on hardware: rapid taps across a switch, a lost reply, and a Wi-Fi blip that must not reclaim
 * navigation mid-press.
 */
public class InputCoreTest {

    private static final long T0 = 100000L;
    /** Comfortably past the reclaim dwell, so a healthy Wi-Fi is eligible again. */
    private static final long T1 = T0 + 13000L;

    /** Wi-Fi healthy (two probes, so the probe streak and dwell are both satisfied) and BT ready. */
    private InputCore wifiAndBt() {
        InputCore c = new InputCore();
        c.onWifiProbe(true, true, T0);
        c.onWifiProbe(true, true, T1);
        c.onBtState(true, true);
        return c;
    }

    @Test
    public void autoPrefersWifiWhenHealthy() {
        InputCore c = wifiAndBt();
        assertEquals(InputCore.Route.WIFI, c.route());
        assertEquals(InputCore.Route.WIFI, c.submit(T1).route);
    }

    @Test
    public void autoUsesBluetoothWhenWifiIsDown() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        assertEquals(InputCore.Route.BT, c.route());
        InputCore.Decision d = c.submit(T0);
        assertEquals(InputCore.Route.BT, d.route);
        assertTrue(d.sendable());
    }

    @Test
    public void noRouteWhenNothingIsReady() {
        InputCore c = new InputCore();
        assertEquals(InputCore.Route.NONE, c.route());
        assertFalse(c.submit(T0).sendable());
        assertFalse(c.canControl());
    }

    @Test
    public void aHardWifiFailureMovesTheNextPressToBluetooth() {
        InputCore c = wifiAndBt();
        InputCore.Decision first = c.submit(T1);
        assertEquals(InputCore.Route.WIFI, first.route);
        c.onResult(InputCore.Outcome.NOT_SENT, T1 + 10);
        // The press that failed is gone; the next fresh press takes the other route.
        assertEquals(InputCore.Route.BT, c.submit(T1 + 20).route);
    }

    @Test
    public void unknownIsNeverTreatedAsUndelivered() {
        InputCore c = wifiAndBt();
        InputCore.Decision d = c.submit(T1);
        assertEquals(1, d.seq);
        c.onResult(InputCore.Outcome.UNKNOWN, T1 + 5);
        // One unconfirmed press must not change the route on its own: it may have landed.
        assertEquals(InputCore.Route.WIFI, c.route());
        // A second one is enough evidence that the route is not working.
        InputCore.Decision second = c.submit(T1 + 10);
        assertEquals(2, second.seq);
        c.onResult(InputCore.Outcome.UNKNOWN, T1 + 15);
        assertEquals(InputCore.Route.BT, c.submit(T1 + 20).route);
    }

    @Test
    public void authFailureIsDistinctFromATemporaryOutage() {
        InputCore c = wifiAndBt();
        assertTrue(c.wifiReady());
        c.onAuthFailed();
        assertFalse(c.wifiReady());
        c.onWifiProbe(true, false, T1 + 100);
        assertFalse(c.wifiReady());
        // Bluetooth still carries input while the pairing is repaired.
        assertEquals(InputCore.Route.BT, c.route());
    }

    @Test
    public void wifiOnlyModeNeverUsesBluetooth() {
        InputCore c = new InputCore();
        c.setMode(InputCore.Mode.WIFI);
        c.onBtState(true, true);
        assertEquals(InputCore.Route.NONE, c.route());
        c.onWifiProbe(false, false, T0);
        assertEquals(InputCore.Route.NONE, c.route());
        assertFalse(c.submit(T0).sendable());
    }

    @Test
    public void bluetoothOnlyModeIgnoresHealthyWifi() {
        InputCore c = new InputCore();
        c.setMode(InputCore.Mode.BT);
        c.onWifiProbe(true, true, T0);
        c.onWifiProbe(true, true, T1);
        c.onBtState(true, true);
        assertEquals(InputCore.Route.BT, c.route());
    }

    @Test
    public void aSingleGoodReplyDoesNotReclaimWifi() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        assertEquals(InputCore.Route.BT, c.route());
        c.onWifiProbe(true, true, T0);
        assertEquals(InputCore.Route.BT, c.route());
        // Two probes but no dwell time yet.
        c.onWifiProbe(true, true, T0 + 500);
        assertEquals(InputCore.Route.BT, c.route());
        assertFalse(c.reclaimDwellMet(T0 + 500));
    }

    @Test
    public void wifiIsReclaimedAfterSustainedHealth() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        c.onWifiProbe(true, true, T0);
        c.onWifiProbe(true, true, T1);
        assertTrue(c.reclaimDwellMet(T1));
        assertEquals(InputCore.Route.WIFI, c.route());
    }

    @Test
    public void noReclaimWhileAPressIsOutstanding() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        c.onWifiProbe(true, true, T0);
        // A press is in flight on Bluetooth; navigation must not be yanked mid-gesture.
        assertEquals(InputCore.Route.BT, c.submit(T0 + 1000).route);
        c.onWifiProbe(true, true, T0 + 20000);
        assertEquals(InputCore.Route.BT, c.route());
        c.onResult(InputCore.Outcome.DELIVERED, T0 + 20050);
        assertEquals(InputCore.Route.WIFI, c.route());
    }

    @Test
    public void switchNoticeIsDeliveredOnce() {
        InputCore c = wifiAndBt();
        c.onWifiProbe(false, false, T1 + 10);
        assertEquals(InputCore.Route.BT, c.route());
        assertNotNull(c.takeNotice());
        assertNull(c.takeNotice());
    }

    @Test
    public void bluetoothDisconnectFallsBackWithoutLosingWifi() {
        InputCore c = wifiAndBt();
        c.onBtState(true, false);
        assertEquals(InputCore.Route.WIFI, c.route());
        assertTrue(c.wifiReady());
    }

    @Test
    public void explicitPreferenceChangeOverridesHistory() {
        InputCore c = wifiAndBt();
        c.setMode(InputCore.Mode.BT);
        assertEquals(InputCore.Route.BT, c.route());
        c.setMode(InputCore.Mode.AUTO);
        assertEquals(InputCore.Route.WIFI, c.route());
    }

    @Test
    public void bluetoothOnlyWithNoConnectionHasNoRoute() {
        InputCore c = new InputCore();
        c.setMode(InputCore.Mode.BT);
        c.onBtState(true, false);
        assertEquals(InputCore.Route.NONE, c.route());
        assertFalse(c.canControl());
    }

    @Test
    public void aBluetoothResultDoesNotRewriteWifiHealth() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        // Wi-Fi is healthy enough to be eligible, but Bluetooth is carrying the presses.
        c.onWifiProbe(true, true, T0);
        InputCore.Route r = c.route();
        assertEquals(InputCore.Route.BT, r);
        c.submit(T0 + 10);
        // A Bluetooth timeout must not be read as evidence that Wi-Fi is down or that it works.
        c.onResult(InputCore.Outcome.UNKNOWN, InputCore.Route.BT, T0 + 20);
        c.onResult(InputCore.Outcome.UNKNOWN, InputCore.Route.BT, T0 + 30);
        assertEquals(InputCore.Route.BT, c.route());
        c.onWifiProbe(true, true, T1);
        assertEquals(InputCore.Route.WIFI, c.route());
    }

    @Test
    public void abandonedPressReleasesTheReclaimGate() {
        InputCore c = new InputCore();
        c.onBtState(true, true);
        c.onWifiProbe(true, true, T0);
        c.submit(T0 + 100);              // press in flight on Bluetooth
        c.onWifiProbe(true, true, T1);   // Wi-Fi healthy again, dwell met
        assertEquals(InputCore.Route.BT, c.route());
        // The press was dropped before it was sent (queue overflow / staleness): nothing was
        // delivered, the transport is not to blame, and the in-flight gate must clear.
        c.onAbandoned(T1 + 10);
        assertFalse(c.pressInFlight());
        assertEquals(InputCore.Route.WIFI, c.route());
    }
}
