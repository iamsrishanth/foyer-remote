package com.kaera.foyer.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service that owns the Bluetooth HID registration for as long as the app runs.
 *
 * <p>This service exists for one reason: AOSP's HID device service unregisters an app whose uid
 * importance rises above IMPORTANCE_VISIBLE (200). A foreground service sits at 125, a plain
 * backgrounded Activity at 400, so without this the projector drops the remote as soon as the
 * phone screen goes off. Everything else here (registration recovery, reconnect backoff) follows
 * from the same goal of keeping the link alive without user attention.
 *
 * <p>It also single threads the tap path so two reports can never interleave mid press.
 */
public final class HidService extends Service {

    private static final String TAG = "FoyerHidService";

    public static final String ACTION_START = "com.kaera.foyer.remote.action.HID_START";

    /** Null when the service is not running. */
    public static volatile HidService instance;

    private static final String CHANNEL_ID = "foyer_remote";
    private static final int NOTIFICATION_ID = 41;
    private static final String PREFS = "foyer_remote_hid";
    private static final String KEY_MAC = "projector_mac";

    /** The HID device role only exists from Android 9 (API 28). */
    private static final int MIN_SDK_HID_DEVICE = 28;

    /** Reconnect backoff after a drop: fast at first, then capped so a dozing phone recovers. */
    private static final long[] BACKOFF_MS = {2000L, 4000L, 8000L, 16000L, 30000L};
    private static final int MAX_REREGISTER = 5;
    private static final long REREGISTER_DELAY_MS = 1000L;
    /** Bounded startup window: the profile proxy is asynchronous, so 20 tries of 500 ms. */
    private static final int BOOTSTRAP_MAX_TRIES = 20;
    private static final long BOOTSTRAP_GAP_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService taps = Executors.newSingleThreadExecutor();
    private final AtomicInteger queued = new AtomicInteger();

    private BtHid hid;
    private volatile boolean running;
    private volatile boolean stopping;

    private volatile String projectorMac;
    private int reconnectIndex;
    private int lostCount;
    private int bootstrapTries;
    private boolean bootstrapScheduled;
    private boolean proxyRequested;
    private boolean adapterReceiverRegistered;

    /** Starts the service if it is not already running. Safe to call from any Activity hook. */
    public static void ensureStarted(final Context c) {
        if (c == null) {
            return;
        }
        try {
            final Intent intent = new Intent(c, HidService.class);
            intent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(intent);
            } else {
                c.startService(intent);
            }
        } catch (RuntimeException e) {
            // Background start limits (API 31+) or a missing permission. The caller decides what
            // to tell the user, so never throw out of here.
            Log.w(TAG, "HidService could not be started", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Bluetooth remote ready"));
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground failed", e);
        }
        instance = this;
        running = true;
        stopping = false;
        projectorMac = prefs().getString(KEY_MAC, null);

        if (Build.VERSION.SDK_INT < MIN_SDK_HID_DEVICE) {
            // Touching BtHid here would load API 28 classes on an older phone.
            Log.w(TAG, "Bluetooth HID needs Android 9 (API 28); this is " + Build.VERSION.SDK_INT);
            setNotification("Bluetooth remote needs Android 9 or newer");
            return;
        }
        hid = new BtHid(this);
        hid.setServiceListener(hidEvents);
        registerAdapterReceiver();
        bootstrap();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        // START_STICKY can bring us back with a null intent, so nothing here may depend on it.
        if (hid != null && !stopping && !hid.isRegistered()) {
            bootstrap();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (adapterReceiverRegistered) {
            try {
                unregisterReceiver(adapterReceiver);
            } catch (RuntimeException ignored) {
                // Already gone; nothing to restore.
            }
            adapterReceiverRegistered = false;
        }
        taps.shutdownNow();
        final BtHid h = hid;
        if (h != null) {
            try {
                h.releaseAll();
            } catch (RuntimeException e) {
                Log.w(TAG, "releaseAll on destroy", e);
            }
            try {
                h.unregister();
            } catch (RuntimeException e) {
                Log.w(TAG, "unregister on destroy", e);
            }
            try {
                h.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "stop on destroy", e);
            }
            try {
                h.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "release on destroy", e);
            }
        }
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ public surface

    /** The HID transport, or null below API 28 and after teardown. */
    public BtHid hid() {
        return hid;
    }

    /** True while a queued or executing press is in flight. */
    public boolean busy() {
        final BtHid h = hid;
        return queued.get() > 0 || (h != null && h.busy());
    }

    /**
     * Queues one press and release on the single report thread. Not in the original contract but
     * this is the only path that makes busy() meaningful; BtHid.tap() stays available for a
     * caller that wants the result synchronously.
     */
    public void tap(final Key key) {
        if (key == null) {
            return;
        }
        final BtHid h = hid;
        if (h == null) {
            return;
        }
        queued.incrementAndGet();
        try {
            taps.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        h.tap(key);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "tap " + key + " failed", e);
                    } finally {
                        queued.decrementAndGet();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            // Executor is shut down because the service is stopping.
            queued.decrementAndGet();
            Log.w(TAG, "tap " + key + " rejected", rejected);
        }
    }

    /**
     * Connects to a projector and remembers it, so a dropped link is retried without the UI
     * having to pass the address again. Returns what BtHid.connectTo() returned.
     */
    public boolean connectTo(final String mac) {
        if (mac == null || mac.trim().isEmpty()) {
            return false;
        }
        rememberMac(mac.trim());
        final BtHid h = hid;
        return h != null && h.connectTo(mac.trim());
    }

    /**
     * Forgets the remembered projector and drops any link, so the next association is explicit.
     * Needed because a bond list also contains speakers and headsets, and picking one by mistake
     * would otherwise be retried forever.
     */
    public void forgetProjector() {
        prefs().edit().remove(KEY_MAC).apply();
        projectorMac = null;
        reconnectIndex = 0;
        handler.removeCallbacks(connectToRemembered);
        handler.removeCallbacks(reconnectTask);
        final BtHid h = hid;
        if (h != null) {
            h.releaseAll();
            h.disconnect();
        }
        setNotification("Bluetooth remote not connected");
    }

    // ------------------------------------------------------------------ HID events

    private final BtHid.Listener hidEvents = new BtHid.Listener() {

        @Override
        public void onHidRegistered(final boolean registered) {
            if (registered) {
                lostCount = 0;
                bootstrapTries = 0;
                setNotification("Bluetooth remote ready");
                // connect() only worked after the app was registered in the probe, so wait for
                // this callback rather than racing registration at startup.
                handler.removeCallbacks(connectToRemembered);
                handler.postDelayed(connectToRemembered, 300L);
                return;
            }
            onRegistrationLost();
        }

        @Override
        public void onHidConnectionState(final int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                reconnectIndex = 0;
                handler.removeCallbacks(reconnectTask);
                final BtHid h = hid;
                final String mac = h == null ? null : h.connectedAddress();
                if (mac != null) {
                    rememberMac(mac);
                }
                setNotification("Bluetooth remote connected");
                return;
            }
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (!running || stopping) {
                    return;
                }
                setNotification("Reconnecting…");
                scheduleReconnect();
                return;
            }
            // CONNECTING or DISCONNECTING: the stack is already working on it, possibly because
            // the UI asked for a specific projector, so stop our own retries.
            handler.removeCallbacks(reconnectTask);
        }

        @Override
        public void onHidLog(final String line) {
            Log.i(TAG, line);
        }
    };

    /**
     * A lost registration is recoverable: the probe saw the connection come straight back after a
     * re-register. It is capped so a genuinely broken stack cannot spin forever.
     */
    private void onRegistrationLost() {
        if (!running || stopping) {
            return;
        }
        lostCount++;
        if (lostCount > MAX_REREGISTER) {
            Log.w(TAG, "registration lost after " + MAX_REREGISTER + " re-register attempts");
            setNotification("Bluetooth remote lost");
            return;
        }
        Log.i(TAG, "registration lost, re-registering (" + lostCount + "/" + MAX_REREGISTER + ")");
        handler.removeCallbacks(reregisterTask);
        handler.postDelayed(reregisterTask, REREGISTER_DELAY_MS);
    }

    private final Runnable reregisterTask = new Runnable() {
        @Override
        public void run() {
            final BtHid h = hid;
            if (!running || stopping || h == null) {
                return;
            }
            final boolean accepted = h.register();
            Log.i(TAG, "re-register accepted=" + accepted);
            if (!accepted) {
                onRegistrationLost();
            }
        }
    };

    // ------------------------------------------------------------------ startup and reconnect

    /**
     * Registration can only be requested once the profile proxy exists, and the proxy is
     * asynchronous, so try a bounded number of times instead of blocking or looping forever.
     */
    private void bootstrap() {
        if (!running || stopping || hid == null) {
            return;
        }
        if (hid.isRegistered() || bootstrapScheduled) {
            return;
        }
        bootstrapScheduled = true;
        handler.post(bootstrapTask);
    }

    private final Runnable bootstrapTask = new Runnable() {
        @Override
        public void run() {
            bootstrapScheduled = false;
            if (!running || stopping || hid == null) {
                return;
            }
            if (hid.isRegistered()) {
                bootstrapTries = 0;
                return;
            }
            if (bootstrapTries >= BOOTSTRAP_MAX_TRIES) {
                Log.w(TAG, "HID registration did not complete: Bluetooth off or Nearby devices"
                        + " permission missing");
                setNotification("Bluetooth remote unavailable");
                bootstrapTries = 0;
                return;
            }
            bootstrapTries++;
            if (!proxyRequested) {
                proxyRequested = hid.start();
            }
            final boolean accepted = hid.register();
            if (!accepted) {
                bootstrapScheduled = true;
                handler.postDelayed(bootstrapTask, BOOTSTRAP_GAP_MS);
                return;
            }
            // Watchdog: if the status callback never arrives, keep the bounded retries going.
            bootstrapScheduled = true;
            handler.postDelayed(bootstrapTask, 1500L);
        }
    };

    private final Runnable connectToRemembered = new Runnable() {
        @Override
        public void run() {
            final BtHid h = hid;
            if (!running || stopping || h == null) {
                return;
            }
            if (h.isConnected()) {
                return;
            }
            final String mac = projectorMac;
            if (mac == null) {
                return;
            }
            final boolean accepted = h.connectTo(mac);
            Log.i(TAG, "connect(" + mac + ") accepted=" + accepted);
            if (!accepted) {
                scheduleReconnect();
            }
        }
    };

    private void scheduleReconnect() {
        if (!running || stopping || projectorMac == null) {
            return;
        }
        final int index = Math.min(reconnectIndex, BACKOFF_MS.length - 1);
        if (reconnectIndex < BACKOFF_MS.length - 1) {
            reconnectIndex++;
        }
        handler.removeCallbacks(reconnectTask);
        handler.postDelayed(reconnectTask, BACKOFF_MS[index]);
        Log.i(TAG, "reconnect in " + BACKOFF_MS[index] + " ms");
    }

    private final Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            final BtHid h = hid;
            if (!running || stopping || h == null) {
                return;
            }
            if (h.isConnected()) {
                reconnectIndex = 0;
                return;
            }
            final String mac = projectorMac;
            if (mac == null) {
                return;
            }
            if (!h.isRegistered()) {
                // A dropped registration is the other way the link dies; recover it first.
                h.register();
            }
            Log.i(TAG, "reconnect(" + mac + ") accepted=" + h.connectTo(mac));
            scheduleReconnect();
        }
    };

    // ------------------------------------------------------------------ adapter state

    /**
     * Bluetooth being switched off is a normal case on a phone, and the profile proxy does not
     * come back on its own when it is switched on again.
     */
    private final BroadcastReceiver adapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final int state = intent == null ? -1
                    : intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            Log.i(TAG, "adapter state=" + state);
            if (state == BluetoothAdapter.STATE_ON) {
                proxyRequested = false;
                bootstrapTries = 0;
                bootstrapScheduled = false;
                bootstrap();
            }
        }
    };

    private void registerAdapterReceiver() {
        try {
            registerReceiver(adapterReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            adapterReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "could not watch adapter state", e);
        }
    }

    // ------------------------------------------------------------------ notification

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void rememberMac(final String mac) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        projectorMac = mac;
        prefs().edit().putString(KEY_MAC, mac).apply();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            createChannelApi26();
        }
    }

    /** Kept in its own method so a pre-26 JVM never has to resolve NotificationChannel. */
    private void createChannelApi26() {
        final NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Foyer Remote",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Bluetooth remote link to the projector");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private void setNotification(final String text) {
        try {
            final NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notification update failed", e);
        }
    }

    private Notification buildNotification(final String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return buildNotificationApi26(text);
        }
        final Notification.Builder builder = new Notification.Builder(this);
        builder.setPriority(Notification.PRIORITY_LOW);
        return fill(builder, text).build();
    }

    /** Kept apart so the channel-aware constructor is never resolved on older devices. */
    private Notification buildNotificationApi26(final String text) {
        return fill(new Notification.Builder(this, CHANNEL_ID), text).build();
    }

    private Notification.Builder fill(final Notification.Builder builder, final String text) {
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return builder
                .setContentTitle("Foyer Remote")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open);
    }
}
