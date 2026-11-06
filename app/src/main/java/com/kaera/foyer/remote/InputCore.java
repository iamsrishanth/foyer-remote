package com.kaera.foyer.remote;

/**
 * Decides which transport a press travels on, and nothing else.
 *
 * <p>Pure Java on purpose: no android imports, no threads, no I/O, so the interesting parts (rapid
 * taps during a switch, a lost reply, a short Wi-Fi blip) can be tested on a desktop JVM instead of
 * being guessed at on a phone.
 *
 * <p>The rules it enforces:
 * <ul>
 *   <li>One press is dispatched on exactly one transport. {@link #submit} answers with a single
 *       route and the caller must not try the other one afterwards.</li>
 *   <li>A press that may already have landed is never resent. Only {@link Outcome#NOT_SENT} and
 *       {@link Outcome#REJECTED} prove non-delivery, and even those do not trigger a resend here -
 *       they only make the next <em>fresh</em> press take the other route.</li>
 *   <li>Going back to Wi-Fi is deliberately slow. It needs a sustained run of successful probes, a
 *       minimum dwell time and no press in flight, so a single lucky reply cannot yank navigation
 *       away mid-use.</li>
 *   <li>Authentication failure and temporary backend failure stay distinct: only the former means
 *       the pairing is gone.</li>
 * </ul>
 */
public final class InputCore {

    /** What the user asked for. */
    public enum Mode {
        /** Prefer Wi-Fi while it is demonstrably healthy, fall back to Bluetooth when it is not. */
        AUTO,
        /** Wi-Fi only. Bluetooth is never used, even when Wi-Fi is failing. */
        WIFI,
        /** Bluetooth only. Wi-Fi may still be used for app list / Jellyfin. */
        BT
    }

    /** How a press actually left the phone. */
    public enum Route { WIFI, BT, NONE }

    /** What the transport reported back. */
    public enum Outcome {
        /** The command was accepted and applied (Wi-Fi: status injected/foyer). */
        DELIVERED,
        /** Provably not delivered: refused connection, helper not running, launcher not running. */
        NOT_SENT,
        /** Written but unconfirmed. The key may already have landed; never resend it. */
        UNKNOWN,
        /** Reached the projector but the command was refused (bad action, blocked target). */
        REJECTED,
        /** The projector rejected the token: the pairing has to be repeated. */
        AUTH_FAILED
    }

    /** Two unconfirmed presses in a row are treated as a degraded route. */
    private static final int UNKNOWN_STREAK_LIMIT = 2;
    /** Wi-Fi must be healthy for this long before it wins navigation back from Bluetooth. */
    private static final long RECOVER_DWELL_MS = 12000;
    /** and for this many consecutive successful probes. */
    private static final int RECOVER_PROBES = 2;

    private Mode mode = Mode.AUTO;

    private boolean wifiReachable;
    private boolean wifiAuthed;
    private long wifiHealthySince;
    private int wifiProbeStreak;
    private int unknownStreak;

    private boolean btUsable;
    private boolean btConnected;

    private Route route = Route.NONE;
    private long seq;
    private int inFlight;
    private String notice;
    /** Last timestamp any caller supplied; route decisions use it so they stay deterministic. */
    private long now;

    /** Snapshot the caller acts on. Exactly one route, one sequence number. */
    public static final class Decision {
        public final Route route;
        public final long seq;

        Decision(Route route, long seq) {
            this.route = route;
            this.seq = seq;
        }

        public boolean sendable() {
            return route != Route.NONE;
        }
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized void setMode(Mode next) {
        if (next == null || next == mode) return;
        mode = next;
        reevaluate("preference changed");
    }

    public synchronized Route route() {
        return route;
    }

    /** True when the current route can carry a press right now. */
    public synchronized boolean canControl() {
        return route != Route.NONE;
    }

    /** True when Wi-Fi is paired and answering, whether or not it currently carries navigation. */
    public synchronized boolean wifiReady() {
        return wifiReachable && wifiAuthed;
    }

    public synchronized boolean btReady() {
        return btUsable && btConnected;
    }

    /** Consumes the one-shot "we changed route" message, or null. */
    public synchronized String takeNotice() {
        String n = notice;
        notice = null;
        return n;
    }

    public synchronized void onWifiProbe(boolean reachable, boolean authenticated, long now) {
        this.now = now;
        boolean was = wifiReachable;
        wifiReachable = reachable;
        if (!reachable) {
            wifiProbeStreak = 0;
            wifiHealthySince = 0;
            if (was) unknownStreak = 0;
        } else {
            wifiProbeStreak++;
            if (wifiHealthySince == 0) wifiHealthySince = now;
            wifiAuthed = authenticated || wifiAuthed;
        }
        if (!authenticated && reachable) {
            // A 401 is the projector saying the token is gone; that is not a temporary outage.
            wifiAuthed = false;
        }
        reevaluate("probe");
    }

    /** The projector answered a press with 401/403. The pairing, not the network, is the problem. */
    public synchronized void onAuthFailed() {
        wifiAuthed = false;
        reevaluate("auth");
    }

    /** Bluetooth registration plus profile connection, as reported by the HID service. */
    public synchronized void onBtState(boolean usable, boolean connected) {
        btUsable = usable;
        btConnected = usable && connected;
        reevaluate("bluetooth");
    }

    /**
     * Called once per user press. The returned route is the only transport that may carry it.
     */
    public synchronized Decision submit(long now) {
        this.now = now;
        seq++;
        Decision d = new Decision(route, seq);
        if (route != Route.NONE) inFlight++;
        return d;
    }

    /**
     * Called when a dispatched press finishes. Never resends; only updates health and lets the
     * route change for the <em>next</em> press.
     *
     * <p>{@code via} is the transport that actually carried the press. A Bluetooth result must not
     * rewrite Wi-Fi health: with Auto, a key carried by Bluetooth while Wi-Fi is merely idle would
     * otherwise be read as evidence that Wi-Fi works (or does not).
     */
    public synchronized void onResult(Outcome outcome, Route via, long now) {
        this.now = now;
        if (inFlight > 0) inFlight--;
        if (outcome == null) return;
        switch (outcome) {
            case DELIVERED:
                unknownStreak = 0;
                if (via == Route.WIFI) {
                    if (!wifiReachable) {
                        wifiReachable = true;
                        if (wifiHealthySince == 0) wifiHealthySince = now;
                    }
                    if (!wifiAuthed) wifiAuthed = true;
                }
                break;
            case UNKNOWN:
                // The key may already be applied. Do not resend, but stop trusting the route.
                if (via == Route.WIFI) {
                    unknownStreak++;
                    if (unknownStreak >= UNKNOWN_STREAK_LIMIT) {
                        wifiReachable = false;
                        wifiProbeStreak = 0;
                    }
                }
                break;
            case NOT_SENT:
                if (via == Route.WIFI) {
                    wifiReachable = false;
                    wifiProbeStreak = 0;
                    wifiHealthySince = 0;
                }
                break;
            case REJECTED:
                // The projector answered, so the transport is fine; the command was not.
                break;
            case AUTH_FAILED:
                if (via == Route.WIFI) wifiAuthed = false;
                break;
            default:
                break;
        }
        reevaluate("result");
    }

    /** Kept for callers that do not track the transport; assumes the press used the current route. */
    public synchronized void onResult(Outcome outcome, long now) {
        onResult(outcome, route, now);
    }

    /**
     * A press was dropped after being accepted, before it was sent (queue overflow or staleness).
     * Nothing was delivered and the transport is not to blame, so health is untouched; the press
     * only has to stop counting as "in flight", or Auto could never reclaim Wi-Fi again.
     */
    public synchronized void onAbandoned(long now) {
        this.now = now;
        if (inFlight > 0) inFlight--;
        reevaluate("abandoned");
    }

    /** True when a press is still outstanding; used to keep switching quiet during input. */
    public synchronized boolean pressInFlight() {
        return inFlight > 0;
    }

    // ------------------------------------------------------------------ decision

    private void reevaluate(String reason) {
        Route next;
        switch (mode) {
            case WIFI:
                next = wifiUsable() ? Route.WIFI : Route.NONE;
                break;
            case BT:
                next = btConnected ? Route.BT : Route.NONE;
                break;
            case AUTO:
            default:
                if (route == Route.BT) {
                    next = reclaimWifi() ? Route.WIFI : (btConnected ? Route.BT : fallback());
                } else {
                    next = wifiUsable() ? Route.WIFI : (btConnected ? Route.BT : Route.NONE);
                }
                break;
        }
        if (next != route) {
            String previous = name(route);
            route = next;
            notice = previous == null ? null : describe(previous, next);
        }
    }

    /**
     * Reclaiming Wi-Fi needs more than one good reply: a full dwell time, a probe streak, and no
     * press in flight. This is what stops navigation flapping during a burst of taps.
     */
    private boolean reclaimWifi() {
        if (!wifiUsable()) return false;
        if (inFlight > 0) return false;
        if (wifiProbeStreak < RECOVER_PROBES) return false;
        if (wifiHealthySince == 0) return false;
        return now - wifiHealthySince >= RECOVER_DWELL_MS;
    }

    private boolean wifiUsable() {
        return wifiReachable && wifiAuthed;
    }

    private Route fallback() {
        return wifiUsable() ? Route.WIFI : Route.NONE;
    }

    private static String describe(String from, Route to) {
        if (to == Route.BT) return "Switched to Bluetooth";
        if (to == Route.WIFI) return "Back on Wi-Fi";
        return null;
    }

    private String name(Route r) {
        return r == Route.WIFI ? "Wi-Fi" : r == Route.BT ? "Bluetooth" : null;
    }

    /** True when enough time has passed for a Bluetooth to Wi-Fi reclaim to be considered. */
    public synchronized boolean reclaimDwellMet(long now) {
        this.now = now;
        return wifiHealthySince != 0 && now - wifiHealthySince >= RECOVER_DWELL_MS;
    }

    /** Exposed for tests and the diagnostics panel. */
    public synchronized String debug() {
        return "mode=" + mode + " route=" + route + " wifi=" + wifiReachable + "/" + wifiAuthed
                + " probes=" + wifiProbeStreak + " bt=" + btUsable + "/" + btConnected
                + " inflight=" + inFlight + " unk=" + unknownStreak;
    }
}
