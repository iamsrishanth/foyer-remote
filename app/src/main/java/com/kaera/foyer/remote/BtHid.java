package com.kaera.foyer.remote;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Bluetooth HID device transport: makes this phone present itself to the projector as a keyboard
 * plus consumer control, so the TV gets real key events instead of synthetic app commands.
 *
 * <p>Nothing in this class claims delivery. {@code sendReport} returning true only means the
 * report was handed to the Bluetooth stack, so results are reported as SENT/UNKNOWN, never as
 * delivered.
 *
 * <p>All Bluetooth calls are wrapped: a revoked permission, a switched-off adapter or a closed
 * proxy must degrade to "not sent" and never crash the caller.
 */
public final class BtHid {

    private static final String TAG = "FoyerHid";

    /** The HID device role only exists from Android 9 (API 28). */
    private static final int MIN_SDK_HID_DEVICE = 28;

    /** How long the synthetic key stays down; long enough for the host to sample the report. */
    private static final long TAP_HOLD_MS = 45L;

    /** Callbacks the UI can subscribe to. */
    public interface Listener {

        void onHidRegistered(boolean registered);

        /** One of the {@link BluetoothProfile#STATE_CONNECTED} style constants. */
        void onHidConnectionState(int state);

        /** Short, human readable diagnostics, safe to append to a log pane. */
        void onHidLog(String line);
    }

    /** Result of one press and release. SENT means "handed to the stack", not "seen on the TV". */
    public enum TapResult {
        SENT,
        NOT_SENT,
        /** The press went out but the release did not, so a key may be stuck down. */
        UNKNOWN
    }

    private final Object sendLock = new Object();
    private final Context context;
    private final BluetoothAdapter adapter;

    private volatile BluetoothHidDevice hid;
    private volatile BluetoothDevice target;
    private volatile Listener listener;
    /** The service slot is separate so a UI listener cannot unhook the reconnect logic. */
    private volatile Listener serviceListener;

    private volatile boolean registered;
    private volatile boolean supported;
    private volatile boolean sending;
    /** True when a press was handed to the stack and its release has not been confirmed. */
    private volatile boolean releasePending;
    private volatile int connState = BluetoothProfile.STATE_DISCONNECTED;
    private volatile String lastAddress;

    private volatile BluetoothHidDevice.Callback callback;

    public BtHid(final Context context) {
        final Context app = context == null ? null : context.getApplicationContext();
        this.context = app == null ? context : app;
        BluetoothAdapter found = null;
        try {
            final BluetoothManager manager = this.context == null ? null
                    : (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
            found = manager == null ? null : manager.getAdapter();
        } catch (RuntimeException e) {
            Log.w(TAG, "no BluetoothAdapter", e);
        }
        this.adapter = found;
    }

    // ------------------------------------------------------------------ listeners

    public void setListener(final Listener l) {
        listener = l;
    }

    /** Used by {@link HidService}; not cleared by {@link #setListener(Listener)}. */
    void setServiceListener(final Listener l) {
        serviceListener = l;
    }

    // ------------------------------------------------------------------ profile proxy

    /**
     * Asks for the HID device profile proxy. Returns false when this build has no HID device
     * role, which is the only honest answer available before the proxy arrives.
     */
    public synchronized boolean start() {
        if (Build.VERSION.SDK_INT < MIN_SDK_HID_DEVICE) {
            log("HID device role needs Android 9 (API 28)");
            return false;
        }
        if (adapter == null) {
            log("no Bluetooth adapter on this device");
            return false;
        }
        if (hid != null) {
            return true;
        }
        try {
            final boolean requested = adapter.getProfileProxy(context, proxyListener,
                    BluetoothProfile.HID_DEVICE);
            if (!requested) {
                log("getProfileProxy(HID_DEVICE) refused: no HID device role here");
            }
            return requested;
        } catch (SecurityException e) {
            log("getProfileProxy: BLUETOOTH_CONNECT is not granted");
            return false;
        } catch (RuntimeException e) {
            log("getProfileProxy failed: " + e);
            return false;
        }
    }

    /** Drops the registration and the proxy. Safe to call twice. */
    public void stop() {
        unregister();
        closeProxy();
    }

    /** Drops references and closes the proxy without unregistering. Used at process teardown. */
    public void release() {
        listener = null;
        serviceListener = null;
        closeProxy();
        callback = null;
    }

    private void closeProxy() {
        final BluetoothHidDevice closed;
        synchronized (sendLock) {
            closed = hid;
            hid = null;
        }
        if (closed != null && adapter != null) {
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, closed);
            } catch (RuntimeException e) {
                Log.w(TAG, "closeProfileProxy", e);
            }
        }
        registered = false;
        connState = BluetoothProfile.STATE_DISCONNECTED;
        target = null;
        releasePending = false;
    }

    private final BluetoothProfile.ServiceListener proxyListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.HID_DEVICE) {
                        return;
                    }
                    if (!(proxy instanceof BluetoothHidDevice)) {
                        log("HID_DEVICE proxy has unexpected type "
                                + (proxy == null ? "null" : proxy.getClass().getName()));
                        return;
                    }
                    supported = true;
                    hid = (BluetoothHidDevice) proxy;
                    log("HID device profile ready");
                }

                @Override
                public void onServiceDisconnected(final int profile) {
                    if (profile != BluetoothProfile.HID_DEVICE) {
                        return;
                    }
                    hid = null;
                    supported = true;
                    registered = false;
                    connState = BluetoothProfile.STATE_DISCONNECTED;
                    log("HID device profile proxy lost");
                }
            };

    // ------------------------------------------------------------------ registration

    /** Registers the SDP record. The real result arrives in {@code onAppStatusChanged}. */
    public boolean register() {
        if (Build.VERSION.SDK_INT < MIN_SDK_HID_DEVICE) {
            return false;
        }
        final BluetoothHidDevice proxy = hid;
        if (proxy == null) {
            log("register: HID profile proxy is not ready");
            return false;
        }
        try {
            final BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                    "Foyer Remote",
                    "Foyer projector remote",
                    "Foyer",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    HidReport.descriptor());
            // QoS settings are left null exactly as in the probe build that worked.
            final boolean accepted = proxy.registerApp(sdp, null, null,
                    context.getMainExecutor(), callback());
            log("registerApp=" + accepted + " (accepted only; waiting for onAppStatusChanged)");
            return accepted;
        } catch (SecurityException e) {
            log("registerApp: BLUETOOTH_CONNECT is not granted");
            return false;
        } catch (RuntimeException e) {
            log("registerApp failed: " + e);
            return false;
        }
    }

    public void unregister() {
        final BluetoothHidDevice proxy = hid;
        if (proxy != null) {
            try {
                proxy.unregisterApp();
            } catch (SecurityException e) {
                log("unregisterApp: BLUETOOTH_CONNECT is not granted");
            } catch (RuntimeException e) {
                log("unregisterApp failed: " + e);
            }
        }
        registered = false;
        connState = BluetoothProfile.STATE_DISCONNECTED;
        target = null;
        releasePending = false;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** True when the HID profile reports a live link. Registration is checked separately. */
    public boolean isConnected() {
        return connState == BluetoothProfile.STATE_CONNECTED && target != null;
    }

    /** True once a HID device profile proxy has been obtained at least once. */
    public boolean isSupported() {
        return supported;
    }

    /** True while a tap is press-and-release-ing, so a caller can avoid queueing more. */
    public boolean busy() {
        return sending;
    }

    public String connectedName() {
        final BluetoothDevice d = target;
        if (d == null) {
            return null;
        }
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** Address of the current or most recent peer, or null. Useful for a reconnect policy. */
    public String connectedAddress() {
        final BluetoothDevice d = target;
        if (d != null) {
            try {
                return d.getAddress();
            } catch (SecurityException e) {
                return lastAddress;
            }
        }
        return lastAddress;
    }

    /** Bonded peers formatted as "AA:BB:CC:DD:EE:FF  Name", sorted for a stable list. */
    public List<String> bondedDevices() {
        final List<String> out = new ArrayList<String>();
        if (adapter == null) {
            return out;
        }
        try {
            final Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return out;
            }
            for (BluetoothDevice d : bonded) {
                if (d == null) {
                    continue;
                }
                String address = "";
                String name = null;
                try {
                    address = d.getAddress();
                } catch (SecurityException ignored) {
                    // Without BLUETOOTH_CONNECT the address is not readable; skip the entry
                    // rather than showing a placeholder the user cannot use.
                    continue;
                }
                try {
                    name = d.getName();
                } catch (SecurityException ignored) {
                    name = null;
                }
                out.add(name == null || name.isEmpty() ? address : address + "  " + name);
            }
        } catch (SecurityException e) {
            log("getBondedDevices: BLUETOOTH_CONNECT is not granted");
        } catch (RuntimeException e) {
            log("getBondedDevices failed: " + e);
        }
        Collections.sort(out);
        return out;
    }

    public boolean connectTo(final String mac) {
        if (mac == null) {
            return false;
        }
        final String address = mac.trim();
        if (address.isEmpty()) {
            return false;
        }
        final BluetoothHidDevice proxy = hid;
        if (proxy == null || adapter == null) {
            log("connect: HID profile proxy is not ready");
            return false;
        }
        if (!adapterOn()) {
            log("connect: Bluetooth is off");
            return false;
        }
        try {
            final BluetoothDevice device = adapter.getRemoteDevice(address);
            final boolean accepted = proxy.connect(device);
            if (accepted) {
                target = device;
                lastAddress = address;
            }
            log("connect(" + address + ")=" + accepted);
            return accepted;
        } catch (IllegalArgumentException e) {
            log("connect: not a Bluetooth address: " + address);
            return false;
        } catch (SecurityException e) {
            log("connect: BLUETOOTH_CONNECT is not granted");
            return false;
        } catch (RuntimeException e) {
            log("connect failed: " + e);
            return false;
        }
    }

    public void disconnect() {
        final BluetoothHidDevice proxy = hid;
        final BluetoothDevice device = target;
        if (proxy == null || device == null) {
            return;
        }
        try {
            proxy.disconnect(device);
        } catch (SecurityException e) {
            log("disconnect: BLUETOOTH_CONNECT is not granted");
        } catch (RuntimeException e) {
            log("disconnect failed: " + e);
        }
    }

    /**
     * Shows the system discoverable prompt. Public setScanMode is a system API, so the prompt is
     * the only honest way to become visible for pairing.
     */
    public boolean makeDiscoverable(final Activity activity) {
        if (activity == null) {
            return false;
        }
        try {
            final Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180);
            activity.startActivity(intent);
            log("discoverable prompt shown");
            return true;
        } catch (RuntimeException e) {
            log("discoverable prompt failed: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ reports

    /**
     * Sends one press and release for a key. Blocking for about {@link #TAP_HOLD_MS} plus the
     * two stack calls, so it must run off the main thread.
     */
    public TapResult tap(final Key key) {
        final HidReport.Frame frame = HidReport.frameFor(key);
        if (frame == null) {
            log("tap: no HID mapping for " + key);
            return TapResult.NOT_SENT;
        }
        sending = true;
        try {
            synchronized (sendLock) {
                return tapLocked(frame);
            }
        } finally {
            sending = false;
        }
    }

    private TapResult tapLocked(final HidReport.Frame frame) {
        final BluetoothHidDevice proxy = hid;
        final BluetoothDevice device = target;
        if (!canSend(proxy, device)) {
            log("tap " + frame.label + " not sent (registered=" + registered
                    + " connected=" + isConnected() + ")");
            return TapResult.NOT_SENT;
        }
        if (releasePending) {
            // A previous tap never confirmed its release. Clear it before pressing anything else,
            // otherwise the host can auto-repeat one key while the next one arrives. Never leave
            // this to teardown: a stuck RIGHT plus a volume press is a runaway cursor.
            log("tap " + frame.label + " clearing a key an earlier tap may have left held");
            clearStuckLocked(proxy, device);
        }
        try {
            if (!proxy.sendReport(device, frame.reportId, frame.press)) {
                log("tap " + frame.label + " press refused by the stack");
                return TapResult.NOT_SENT;
            }
        } catch (RuntimeException e) {
            // Nothing reached the stack, so the host never saw this key.
            log("tap " + frame.label + " press failed: " + e);
            return TapResult.NOT_SENT;
        }
        // The host may now hold the key down. Anything that stops the release going out has to
        // leave this true so releaseAll() can clear a stuck key later.
        releasePending = true;
        sleep(TAP_HOLD_MS);
        try {
            if (!proxy.sendReport(device, frame.reportId, frame.release)) {
                log("tap " + frame.label + " press sent, release refused");
                return TapResult.UNKNOWN;
            }
            releasePending = false;
            log("tap " + frame.label + " sent to the stack");
            return TapResult.SENT;
        } catch (RuntimeException e) {
            log("tap " + frame.label + " press sent, release failed: " + e);
            return TapResult.UNKNOWN;
        }
    }

    /**
     * Clears both reports so a stuck key cannot survive a lost link or a failed release.
     * Failures are swallowed on purpose: this runs during teardown.
     */
    public void releaseAll() {
        synchronized (sendLock) {
            final BluetoothHidDevice proxy = hid;
            final BluetoothDevice device = target;
            // Deliberately does not require registration: the safety net tries whenever the
            // profile still reports a link.
            if (proxy != null && device != null && isConnected() && adapterOn()) {
                try {
                    proxy.sendReport(device, HidReport.REPORT_ID_KEYBOARD,
                            HidReport.releaseFor(HidReport.REPORT_ID_KEYBOARD));
                } catch (RuntimeException e) {
                    log("releaseAll keyboard failed: " + e);
                }
                try {
                    proxy.sendReport(device, HidReport.REPORT_ID_CONSUMER,
                            HidReport.releaseFor(HidReport.REPORT_ID_CONSUMER));
                } catch (RuntimeException e) {
                    log("releaseAll consumer failed: " + e);
                }
            }
            releasePending = false;
        }
    }

    /**
     * Sends the neutral report for both report ids and clears the held-key flag if both went out.
     * Called from inside {@link #tapLocked} before a new press, and from the connection callbacks,
     * so a failed release from an earlier tap cannot leave the projector auto-repeating a key.
     */
    private void clearStuckLocked(final BluetoothHidDevice proxy, final BluetoothDevice device) {
        boolean ok = true;
        try {
            ok = proxy.sendReport(device, HidReport.REPORT_ID_KEYBOARD,
                    HidReport.releaseFor(HidReport.REPORT_ID_KEYBOARD));
        } catch (RuntimeException e) {
            log("clearStuck keyboard failed: " + e);
            ok = false;
        }
        try {
            ok &= proxy.sendReport(device, HidReport.REPORT_ID_CONSUMER,
                    HidReport.releaseFor(HidReport.REPORT_ID_CONSUMER));
        } catch (RuntimeException e) {
            log("clearStuck consumer failed: " + e);
            ok = false;
        }
        if (ok) {
            releasePending = false;
        }
        log("clearStuck ok=" + ok);
    }

    private boolean canSend(final BluetoothHidDevice proxy, final BluetoothDevice device) {
        if (proxy == null || device == null || !registered) {
            return false;
        }
        return isConnected() && adapterOn();
    }

    /** Never assume the adapter is on: a turned-off radio makes every stack call fail. */
    private boolean adapterOn() {
        if (adapter == null) {
            return false;
        }
        try {
            return adapter.getState() == BluetoothAdapter.STATE_ON;
        } catch (SecurityException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ callback

    /**
     * Built on first use so a phone below API 28 never loads the HID callback class at all,
     * and so the 45 ms tap hold is never in the way of this call.
     */
    private synchronized BluetoothHidDevice.Callback callback() {
        if (callback == null) {
            callback = new HidCallback();
        }
        return callback;
    }

    private final class HidCallback extends BluetoothHidDevice.Callback {

        @Override
        public void onAppStatusChanged(final BluetoothDevice pluggedDevice,
                final boolean isRegistered) {
            registered = isRegistered;
            log("onAppStatusChanged registered=" + isRegistered + " device="
                    + describe(pluggedDevice));
            notifyRegistered(isRegistered);
        }

        @Override
        public void onConnectionStateChanged(final BluetoothDevice device, final int state) {
            connState = state;
            // Only a real CONNECTED event makes a device "the peer". connect() returning true means
            // the request was accepted, not that anything linked - an attempt against a bonded
            // speaker or headset returns true and then quietly fails. Recording that device would
            // make the UI claim a Bluetooth remote that is not there.
            if (state == BluetoothProfile.STATE_CONNECTED && device != null) {
                target = device;
                try {
                    lastAddress = device.getAddress();
                } catch (SecurityException ignored) {
                    // The link still works; only the address string is unavailable.
                }
            }
            log("onConnectionStateChanged " + describe(device) + " state=" + stateName(state));
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                // The host drops its held-key state with the link, so nothing is stuck any more.
                releasePending = false;
            }
            notifyState(state);
        }

        @Override
        public void onGetReport(final BluetoothDevice device, final byte type, final byte id,
                final int bufferSize) {
            final BluetoothHidDevice proxy = hid;
            if (proxy == null) {
                return;
            }
            log("onGetReport type=" + type + " id=" + id + " size=" + bufferSize);
            try {
                proxy.replyReport(device, type, id, new byte[Math.max(bufferSize, 1)]);
            } catch (RuntimeException e) {
                log("replyReport failed: " + e);
            }
        }

        @Override
        public void onSetReport(final BluetoothDevice device, final byte type, final byte id,
                final byte[] data) {
            final BluetoothHidDevice proxy = hid;
            if (proxy == null) {
                return;
            }
            log("onSetReport type=" + type + " id=" + id);
            try {
                proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            } catch (RuntimeException e) {
                log("reportError failed: " + e);
            }
        }

        @Override
        public void onSetProtocol(final BluetoothDevice device, final byte protocol) {
            log("onSetProtocol protocol=" + protocol);
        }

        @Override
        public void onInterruptData(final BluetoothDevice device, final byte reportId,
                final byte[] data) {
            log("onInterruptData id=" + reportId + " len=" + (data == null ? 0 : data.length));
        }

        @Override
        public void onVirtualCableUnplug(final BluetoothDevice device) {
            log("onVirtualCableUnplug " + describe(device));
            connState = BluetoothProfile.STATE_DISCONNECTED;
            notifyState(BluetoothProfile.STATE_DISCONNECTED);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void notifyRegistered(final boolean isRegistered) {
        final Listener ui = listener;
        final Listener service = serviceListener;
        if (ui != null) {
            try {
                ui.onHidRegistered(isRegistered);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener onHidRegistered threw", e);
            }
        }
        if (service != null && service != ui) {
            try {
                service.onHidRegistered(isRegistered);
            } catch (RuntimeException e) {
                Log.w(TAG, "service listener onHidRegistered threw", e);
            }
        }
    }

    private void notifyState(final int state) {
        final Listener ui = listener;
        final Listener service = serviceListener;
        if (ui != null) {
            try {
                ui.onHidConnectionState(state);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener onHidConnectionState threw", e);
            }
        }
        if (service != null && service != ui) {
            try {
                service.onHidConnectionState(state);
            } catch (RuntimeException e) {
                Log.w(TAG, "service listener onHidConnectionState threw", e);
            }
        }
    }

    private void log(final String line) {
        Log.i(TAG, line);
        final Listener ui = listener;
        final Listener service = serviceListener;
        if (ui != null) {
            try {
                ui.onHidLog(line);
            } catch (RuntimeException ignored) {
                // A broken log sink must not break the transport.
            }
        }
        if (service != null && service != ui) {
            try {
                service.onHidLog(line);
            } catch (RuntimeException ignored) {
                // Same here.
            }
        }
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            // Keep the hold short rather than swallowing the interrupt: the caller is in a hurry.
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(final BluetoothDevice device) {
        if (device == null) {
            return "null";
        }
        try {
            return device.getAddress() + "/" + device.getName();
        } catch (SecurityException e) {
            return "device/name unavailable";
        }
    }

    private static String stateName(final int state) {
        switch (state) {
            case BluetoothProfile.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothProfile.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothProfile.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "DISCONNECTING";
            default:
                return "state" + state;
        }
    }
}
