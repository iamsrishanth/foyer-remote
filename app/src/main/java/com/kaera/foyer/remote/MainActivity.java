package com.kaera.foyer.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The phone remote.
 *
 * <p>Two transports can carry the same buttons: the projector's Wi-Fi API (rich, but it dies with
 * the projector's Foyer process) and a Bluetooth HID keyboard link (fewer features, but it reaches
 * whatever app is in front even when Foyer is gone). {@link InputCore} decides which one a press
 * uses; this class only renders state and moves bytes.
 *
 * <p>Every visible state is derived from one place, {@link #render()}. That is deliberate: the
 * previous build had about twenty call sites writing the status line and the status dot
 * independently, so the two could disagree and a failure could leave the remote panel hidden.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "foyer_remote";
    private static final int DEFAULT_PORT = 8484;
    /** Six queued presses is about a second of backlog on this projector; older ones are dropped. */
    private static final int KEYS_QUEUE = 6;
    /** A queued press older than this would land in whatever app is in front by then. */
    private static final long STALE_PRESS_MS = 1500;
    /** Comfortably longer than the projector's own bounded command budget (2.8 s). */
    private static final int CMD_TIMEOUT_MS = 4500;
    private static final int REQ_BT_PERMISSIONS = 41;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService bg;
    private ThreadPoolExecutor keys;
    private SharedPreferences prefs;

    private final InputCore core = new InputCore();

    private volatile String ip;
    private volatile String token;
    private volatile int port = DEFAULT_PORT;
    private volatile String deviceName;

    /** Last /api/state detail; used for the honest status line, never for a green light on its own. */
    private volatile boolean a11yOk;
    private volatile boolean helperOk;
    private volatile boolean wifiPaired;
    private volatile String lastWifiProblem;

    private JSONArray lastTiles;
    private JSONArray lastAll;
    private String tilesSignature = "";
    private String jellyfinUrl = "";

    private boolean btSupported;
    private boolean btPermissionAsked;
    private boolean btRegistered;
    private boolean btConnected;
    private boolean btBonded;
    private String btPeerName;
    private String btLastProblem;
    private HidService attachedService;
    private boolean listenerAttached;
    private long lastHidStartAttempt;
    private String notice;
    private long noticeUntil;

    private int dropped;
    private int stale;
    /** Presses accepted by the queue and not yet finished; keeps the queue accounting exact. */
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();

    // views
    private TextView devName;
    private TextView statusText;
    private TextView statusSub;
    private TextView transportNote;
    private TextView noticeBar;
    private TextView padHint;
    private TextView chipLabel;
    private TextView foundLabel;
    private TextView connectMsg;
    private TextView btStatus;
    private TextView jfMsg;
    private TextView diagText;
    private ImageView stateIcon;
    private ImageView chipIcon;
    private View connectChip;
    private View pad;
    private View pairCard;
    private View padSlot;
    private View transportGroup;
    private TextView transportAuto;
    private TextView transportWifi;
    private TextView transportBt;
    private View appsSheet;
    private View settingsSheet;
    private LinearLayout deviceList;
    private LinearLayout btDeviceList;
    private LinearLayout appList;
    private EditText ipInput;
    private EditText pinInput;
    private EditText jfUrl;
    private EditText jfKey;
    private CheckBox allAppsToggle;
    private Button pairAction;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            render();
            ui.postDelayed(this, 4000);
        }
    };

    private final Runnable renderTick = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        bg = Executors.newSingleThreadExecutor();
        keys = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(KEYS_QUEUE),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        wire();

        ip = prefs.getString("ip", null);
        port = prefs.getInt("port", DEFAULT_PORT);
        token = prefs.getString("token", null);
        deviceName = prefs.getString("name", null);
        wifiPaired = ip != null && token != null;

        String storedMode = prefs.getString("mode", InputCore.Mode.AUTO.name());
        try {
            core.setMode(InputCore.Mode.valueOf(storedMode));
        } catch (Exception ignored) {
            core.setMode(InputCore.Mode.AUTO);
        }

        btSupported = btFeaturePresent();
        updateTransportUi();
        render();

        if (wifiPaired) {
            lastWifiProblem = "Looking for " + nameOrIp() + "…";
            render();
        }
        startBtServiceIfUseful();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        startBtServiceIfUseful();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (bg != null) bg.shutdownNow();
        if (keys != null) keys.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        devName = (TextView) findViewById(R.id.devName);
        statusText = (TextView) findViewById(R.id.statusText);
        statusSub = (TextView) findViewById(R.id.statusSub);
        transportNote = (TextView) findViewById(R.id.transportNote);
        noticeBar = (TextView) findViewById(R.id.noticeBar);
        padHint = (TextView) findViewById(R.id.padHint);
        chipLabel = (TextView) findViewById(R.id.chipLabel);
        foundLabel = (TextView) findViewById(R.id.foundLabel);
        connectMsg = (TextView) findViewById(R.id.connectMsg);
        btStatus = (TextView) findViewById(R.id.btStatus);
        jfMsg = (TextView) findViewById(R.id.jfMsg);
        diagText = (TextView) findViewById(R.id.diagText);
        stateIcon = (ImageView) findViewById(R.id.stateIcon);
        chipIcon = (ImageView) findViewById(R.id.chipIcon);
        connectChip = findViewById(R.id.connectChip);
        pad = findViewById(R.id.pad);
        padSlot = findViewById(R.id.padSlot);
        pairCard = findViewById(R.id.pairCard);
        pairAction = (Button) findViewById(R.id.pairAction);
        transportGroup = findViewById(R.id.transportGroup);
        transportAuto = (TextView) findViewById(R.id.transportAuto);
        transportWifi = (TextView) findViewById(R.id.transportWifi);
        transportBt = (TextView) findViewById(R.id.transportBt);
        appsSheet = findViewById(R.id.appsSheet);
        settingsSheet = findViewById(R.id.settingsSheet);
        deviceList = (LinearLayout) findViewById(R.id.deviceList);
        btDeviceList = (LinearLayout) findViewById(R.id.btDeviceList);
        appList = (LinearLayout) findViewById(R.id.appList);
        ipInput = (EditText) findViewById(R.id.ipInput);
        pinInput = (EditText) findViewById(R.id.pinInput);
        jfUrl = (EditText) findViewById(R.id.jfUrl);
        jfKey = (EditText) findViewById(R.id.jfKey);
        allAppsToggle = (CheckBox) findViewById(R.id.allAppsToggle);
    }

    private void wire() {
        key(R.id.up, Key.UP);
        key(R.id.down, Key.DOWN);
        key(R.id.left, Key.LEFT);
        key(R.id.right, Key.RIGHT);
        key(R.id.ok, Key.OK);
        key(R.id.back, Key.BACK);
        key(R.id.home, Key.HOME);
        key(R.id.volUp, Key.VOL_UP);
        key(R.id.volDown, Key.VOL_DOWN);
        key(R.id.mute, Key.MUTE);

        connectChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        findViewById(R.id.appsBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showApps();
            }
        });
        findViewById(R.id.settingsBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        findViewById(R.id.appsClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                appsSheet.setVisibility(View.GONE);
            }
        });
        findViewById(R.id.settingsClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                settingsSheet.setVisibility(View.GONE);
            }
        });
        allAppsToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tilesSignature = "";
                renderApps();
            }
        });
        findViewById(R.id.scanBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                scan();
            }
        });
        findViewById(R.id.pairBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pair();
            }
        });
        findViewById(R.id.jfSave).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveJellyfin();
            }
        });
        findViewById(R.id.forgetBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                forgetWifiPairing();
            }
        });
        findViewById(R.id.diagRefresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                renderDiagnostics();
            }
        });
        findViewById(R.id.recents).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toast("Recents has no effect on this projector");
            }
        });
        findViewById(R.id.btPermissionBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                requestBtPermissions();
            }
        });
        findViewById(R.id.btScanBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                listBtDevices();
            }
        });
        findViewById(R.id.btPairBtn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                makeDiscoverable();
            }
        });
        pairAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSettings();
            }
        });
        transportAuto.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(InputCore.Mode.AUTO);
            }
        });
        transportWifi.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(InputCore.Mode.WIFI);
            }
        });
        transportBt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode(InputCore.Mode.BT);
            }
        });
    }

    private void key(int id, final Key k) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                press(k);
            }
        });
    }

    // ------------------------------------------------------------------ input

    /**
     * One press, one transport, chosen once. The decision is taken on the UI thread so ordering is
     * the user's ordering; the bytes are then written on a single worker so they cannot interleave.
     */
    private void press(final Key k) {
        long now = SystemClock.elapsedRealtime();
        // Refuse before accepting: a press that is accepted but never dispatched would leave
        // InputCore believing a press is still in flight, and Auto would never reclaim Wi-Fi.
        if (pending.get() >= KEYS_QUEUE) {
            dropped++;
            quietNotice("Slow projector · press dropped");
            return;
        }
        final InputCore.Decision d = core.submit(now);
        if (!d.sendable()) {
            core.onAbandoned(now);
            render();
            quietNotice("No control connection");
            return;
        }
        InputCore.Route route = d.route;
        // Per-action capability: a key the chosen transport cannot really deliver is refused here
        // rather than sent into the void. This is why the capability matrix is not just UI text.
        if (route == InputCore.Route.BT && !k.overHid()) {
            core.onAbandoned(now);
            render();
            quietNotice(k == Key.MUTE ? "Mute needs Wi-Fi" : "Not available over Bluetooth");
            return;
        }
        if (route == InputCore.Route.WIFI && !wifiCanCarry(k) && core.btReady()) {
            // Wi-Fi is answering but cannot route this one (Back/Home need the accessibility
            // service); Bluetooth can, so the press goes there instead of doing nothing.
            route = InputCore.Route.BT;
        }
        final InputCore.Route chosen = route;
        final long enqueuedAt = now;
        pending.incrementAndGet();
        keys.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // A press that waited too long would land in whatever app is in front by then:
                    // drop it instead of delivering a delayed burst.
                    if (SystemClock.elapsedRealtime() - enqueuedAt > STALE_PRESS_MS) {
                        stale++;
                        core.onAbandoned(SystemClock.elapsedRealtime());
                        ui.post(new Runnable() {
                            public void run() {
                                quietNotice("Projector lagging · press dropped");
                                render();
                            }
                        });
                        return;
                    }
                    // A queued press that has not been sent yet cannot have landed, so it may still
                    // travel on whichever transport is alive now. Without this, a burst of taps
                    // issued just as Wi-Fi died would all be dispatched on the dead route and the
                    // user would see nothing until the next press. Never re-route a press that was
                    // already sent - that is the one rule that prevents a doubled key.
                    InputCore.Route effective = chosen;
                    InputCore.Route current = core.route();
                    if (current != chosen && current != InputCore.Route.NONE
                            && !(current == InputCore.Route.BT && !k.overHid())) {
                        effective = current;
                    }
                    dispatch(k, effective);
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
        render();
    }

    /** True when the Wi-Fi path can actually carry this key with the projector's current state. */
    private boolean wifiCanCarry(Key k) {
        switch (k) {
            case BACK:
            case HOME:
                return a11yOk;
            default:
                return true;
        }
    }

    private void dispatch(Key k, InputCore.Route route) {
        long started = SystemClock.elapsedRealtime();
        InputCore.Outcome outcome;
        if (route == InputCore.Route.BT) {
            outcome = dispatchHid(k);
        } else {
            outcome = dispatchWifi(k);
        }
        final long took = SystemClock.elapsedRealtime() - started;
        final InputCore.Outcome o = outcome;
        core.onResult(o, route, SystemClock.elapsedRealtime());
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (o == InputCore.Outcome.AUTH_FAILED) {
                    forgetToken();
                    lastWifiProblem = "The projector no longer accepts this phone";
                } else if (o == InputCore.Outcome.UNKNOWN) {
                    quietNotice("Sent, not confirmed");
                } else if (o == InputCore.Outcome.REJECTED) {
                    quietNotice("The projector refused that command");
                }
                if (took > 1200) {
                    quietNotice("Projector is slow to answer");
                }
                render();
            }
        });
    }

    private InputCore.Outcome dispatchWifi(Key k) {
        String host = ip;
        String tok = token;
        if (host == null || tok == null) return InputCore.Outcome.NOT_SENT;
        String path;
        String body;
        switch (k) {
            case BACK:
                path = "/api/global";
                body = "{\"action\":\"back\",\"token\":\"" + tok + "\"}";
                break;
            case HOME:
                path = "/api/global";
                body = "{\"action\":\"home\",\"token\":\"" + tok + "\"}";
                break;
            case VOL_UP:
                path = "/api/volume";
                body = "{\"dir\":\"up\",\"token\":\"" + tok + "\"}";
                break;
            case VOL_DOWN:
                path = "/api/volume";
                body = "{\"dir\":\"down\",\"token\":\"" + tok + "\"}";
                break;
            case MUTE:
                path = "/api/volume";
                body = "{\"dir\":\"mute\",\"token\":\"" + tok + "\"}";
                break;
            default:
                path = "/api/cmd";
                body = "{\"cmd\":\"" + wifiCmd(k) + "\",\"token\":\"" + tok + "\"}";
                break;
        }
        Net.Reply r = Net.postClassified(host, port, path, body, 1200, CMD_TIMEOUT_MS);
        if (r.kind == Net.Kind.REFUSED || r.kind == Net.Kind.NOT_SENT) {
            return InputCore.Outcome.NOT_SENT;
        }
        if (r.kind == Net.Kind.UNKNOWN) {
            return InputCore.Outcome.UNKNOWN;
        }
        if (r.kind == Net.Kind.REJECTED) {
            return InputCore.Outcome.REJECTED;
        }
        if (r.code == 401 || r.code == 403) {
            return InputCore.Outcome.AUTH_FAILED;
        }
        if (r.code >= 500) {
            return InputCore.Outcome.NOT_SENT;
        }
        JSONObject o = parse(r.body);
        if (o == null) return InputCore.Outcome.UNKNOWN;
        String status = o.optString("status", "");
        // The projector answers ok:true for legacy clients even when nothing was routed, so the
        // status field, not the HTTP code, is what decides here.
        if ("unavailable".equals(status)) return InputCore.Outcome.NOT_SENT;
        if ("unknown".equals(status)) return InputCore.Outcome.UNKNOWN;
        if ("rejected".equals(status) || "error".equals(status)) return InputCore.Outcome.REJECTED;
        if (!o.optBoolean("ok", true)) return InputCore.Outcome.REJECTED;
        return InputCore.Outcome.DELIVERED;
    }

    private static String wifiCmd(Key k) {
        switch (k) {
            case UP:
                return "up";
            case DOWN:
                return "down";
            case LEFT:
                return "left";
            case RIGHT:
                return "right";
            default:
                return "ok";
        }
    }

    private InputCore.Outcome dispatchHid(Key k) {
        HidService svc = HidService.instance;
        if (svc == null) return InputCore.Outcome.NOT_SENT;
        BtHid h = svc.hid();
        if (h == null) return InputCore.Outcome.NOT_SENT;
        BtHid.TapResult r = h.tap(k);
        if (r == BtHid.TapResult.NOT_SENT) return InputCore.Outcome.NOT_SENT;
        if (r == BtHid.TapResult.UNKNOWN) return InputCore.Outcome.UNKNOWN;
        return InputCore.Outcome.DELIVERED;
    }

    // ------------------------------------------------------------------ bluetooth plumbing

    private boolean btFeaturePresent() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return true; // cannot tell yet; assume present until the permission says otherwise
        }
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null && bm.getAdapter() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean btPermissionGranted() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < 31) {
            startBtServiceIfUseful();
            return;
        }
        btPermissionAsked = true;
        List<String> wanted = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (wanted.isEmpty()) {
            startBtServiceIfUseful();
            return;
        }
        requestPermissions(wanted.toArray(new String[0]), REQ_BT_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_BT_PERMISSIONS) return;
        boolean all = results.length > 0;
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) all = false;
        }
        btSupported = btFeaturePresent();
        if (all) {
            btLastProblem = null;
            startBtServiceIfUseful();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            btLastProblem = "Bluetooth access is blocked in system settings";
        } else {
            btLastProblem = "Bluetooth access was not granted";
        }
        render();
    }

    private void startBtServiceIfUseful() {
        if (!btSupportUsable()) return;
        HidService.ensureStarted(this);
    }

    /**
     * Self-healing start. {@link #ensureStarted} is fire-and-forget: if the process was killed by
     * a permission change or low memory, Android can refuse or delay the restart and the app would
     * otherwise sit there claiming Bluetooth is unavailable until it is force-stopped and reopened.
     * One retry per poll tick is cheap and stops that dead end.
     */
    private void ensureHidServiceAlive() {
        if (!btSupportUsable()) return;
        if (HidService.instance != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHidStartAttempt < 15000) return;
        lastHidStartAttempt = now;
        HidService.ensureStarted(this);
    }

    /** Bluetooth is only offered when the profile actually exists on this phone. */
    private boolean btSupportUsable() {
        if (!btFeaturePresent()) {
            btSupported = false;
            return false;
        }
        if (!btPermissionGranted()) return false;
        return true;
    }

    private final BtHid.Listener btListener = new BtHid.Listener() {
        public void onHidRegistered(boolean registered) {
            btRegistered = registered;
            if (!registered) btLastProblem = "Bluetooth remote registration was lost";
            else btLastProblem = null;
            ui.post(renderTick);
        }

        public void onHidConnectionState(int state) {
            btConnected = state == 2;
            if (btConnected) btLastProblem = null;
            ui.post(renderTick);
        }

        public void onHidLog(String line) {
            // Kept for the diagnostics panel only; never in the daily-use status line.
            btLastLog = line;
        }
    };

    private volatile String btLastLog;

    private void attachBtListener() {
        HidService svc = HidService.instance;
        if (svc == null) {
            attachedService = null;
            listenerAttached = false;
            return;
        }
        if (attachedService == svc && listenerAttached) return;
        BtHid h = svc.hid();
        if (h == null) return;
        h.setListener(btListener);
        attachedService = svc;
        listenerAttached = true;
        btSupported = h.isSupported();
    }

    private void makeDiscoverable() {
        HidService svc = HidService.instance;
        if (svc == null || svc.hid() == null) {
            btLastProblem = "Bluetooth remote is not running";
            render();
            return;
        }
        svc.hid().makeDiscoverable(this);
        toast("This phone is now visible to the projector for 3 minutes");
    }

    private void listBtDevices() {
        if (btDeviceList == null) return;
        btDeviceList.removeAllViews();
        HidService svc = HidService.instance;
        List<String> bonded = svc == null || svc.hid() == null
                ? new ArrayList<String>() : svc.hid().bondedDevices();
        if (bonded.isEmpty()) {
            addBtRow("No paired projector yet. Make the phone visible, then pair from the"
                    + " projector's own Bluetooth settings.", null);
            return;
        }
        btBonded = true;
        for (String entry : bonded) {
            final String mac = entry.length() >= 17 ? entry.substring(0, 17) : entry;
            final String label = entry.length() > 18 ? entry.substring(18).trim() : entry;
            Button b = addBtRow(label + "\n" + mac, new Runnable() {
                public void run() {
                    connectBt(mac);
                }
            });
            b.setTextSize(13f);
        }
    }

    private Button addBtRow(String text, final Runnable action) {
        LinearLayout list = btDeviceList != null ? btDeviceList : deviceList;
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(getResources().getColor(R.color.text, getTheme()));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        if (action != null) {
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    action.run();
                }
            });
        }
        list.addView(b);
        return b;
    }

    private void connectBt(final String mac) {
        HidService svc = HidService.instance;
        if (svc == null || svc.hid() == null) {
            btLastProblem = "Bluetooth remote is not running";
            render();
            return;
        }
        final BtHid h = svc.hid();
        bg.execute(new Runnable() {
            public void run() {
                final boolean ok = h.connectTo(mac);
                ui.post(new Runnable() {
                    public void run() {
                        if (!ok) btLastProblem = "Could not connect to " + mac;
                        else btLastProblem = "Connecting…";
                        render();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ polling

    private void poll() {
        attachBtListener();
        ensureHidServiceAlive();
        pollBt();
        if (ip == null || token == null) {
            core.onWifiProbe(false, false, SystemClock.elapsedRealtime());
            return;
        }
        final String host = ip;
        final int p = port;
        final String tok = token;
        bg.execute(new Runnable() {
            public void run() {
                Net.Resp r;
                try {
                    r = Net.getResult("http://" + host + ":" + p + "/api/state", 3500);
                } catch (Exception e) {
                    core.onWifiProbe(false, false, SystemClock.elapsedRealtime());
                    lastWifiProblem = "Projector unreachable";
                    ui.post(renderTick);
                    return;
                }
                if (r.code == 401 || r.code == 403) {
                    core.onAuthFailed();
                    lastWifiProblem = "The projector no longer accepts this phone";
                    ui.post(renderTick);
                    return;
                }
                if (r.code >= 500) {
                    core.onWifiProbe(false, false, SystemClock.elapsedRealtime());
                    JSONObject oi = parse(r.body);
                    lastWifiProblem = oi == null ? "Projector remote not running"
                            : oi.optString("error", "Projector remote not running");
                    ui.post(renderTick);
                    return;
                }
                JSONObject o = parse(r.body);
                if (o == null) {
                    core.onWifiProbe(false, false, SystemClock.elapsedRealtime());
                    lastWifiProblem = "Unreadable reply from the projector";
                    ui.post(renderTick);
                    return;
                }
                a11yOk = o.optBoolean("a11y", true);
                JSONObject input = o.optJSONObject("input");
                helperOk = input != null && input.optBoolean("helper", false);
                String n = o.optString("name", host);
                deviceName = n;
                jellyfinUrl = o.optString("jellyfinUrl", "");
                lastTiles = o.optJSONArray("tiles");
                lastAll = o.optJSONArray("all");
                lastWifiProblem = null;
                core.onWifiProbe(true, true, SystemClock.elapsedRealtime());
                ui.post(new Runnable() {
                    public void run() {
                        renderApps();
                        render();
                    }
                });
            }
        });
    }

    private void pollBt() {
        HidService svc = HidService.instance;
        if (svc == null) {
            core.onBtState(false, false);
            return;
        }
        BtHid h = svc.hid();
        if (h == null) {
            core.onBtState(false, false);
            return;
        }
        btSupported = h.isSupported();
        btRegistered = h.isRegistered();
        btConnected = h.isConnected();
        btPeerName = h.connectedName();
        core.onBtState(btSupported && btRegistered, btConnected);
    }

    // ------------------------------------------------------------------ rendering

    /**
     * The single writer for everything the user reads about connection state. Text always says what
     * is true; colour and icon only reinforce it.
     */
    private void render() {
        if (statusText == null) return;
        attachBtListener();

        // Identity: the name once we know it, otherwise the address, otherwise a neutral label.
        String identity = deviceName != null ? deviceName : (ip != null ? ip : "No projector yet");
        devName.setText(identity);

        boolean wifiRoute = core.route() == InputCore.Route.WIFI;
        boolean btRoute = core.route() == InputCore.Route.BT;

        String status;
        String sub = null;
        int icon;
        int tint;
        String chip;
        int chipIconRes;

        if (btRoute) {
            status = "Controlling via Bluetooth";
            sub = btPeerName != null ? btPeerName : "Works in every app";
            if (!btRegistered && btConnected) sub = "Registration lost, reconnecting…";
            icon = R.drawable.ic_bluetooth;
            tint = R.color.ok;
        } else if (wifiRoute) {
            status = "Controlling via Wi-Fi";
            if (!helperOk && !a11yOk) sub = "Works inside Foyer only";
            else if (!helperOk) sub = "D-pad works inside Foyer only";
            else if (!a11yOk) sub = "Back and Home unavailable";
            icon = R.drawable.ic_link;
            tint = R.color.ok;
        } else if (core.wifiReady()) {
            status = "Wi-Fi connected, controls unavailable";
            sub = lastWifiProblem;
            icon = R.drawable.ic_alert;
            tint = R.color.warn;
        } else if (wifiPaired || btBonded) {
            status = "No control connection";
            sub = lastWifiProblem != null ? lastWifiProblem
                    : (btLastProblem != null ? btLastProblem : "Tap Connect to repair");
            icon = R.drawable.ic_alert;
            tint = R.color.bad;
        } else {
            status = "Not connected";
            sub = "Pair with Wi-Fi or Bluetooth";
            icon = R.drawable.ic_alert;
            tint = R.color.dim;
        }

        statusText.setText(status);
        // Landscape on a phone is ~443 dp tall: the secondary lines are dropped there so the pad,
        // the volume row and the bottom row all fit without scrolling. Portrait keeps them.
        boolean shortScreen = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (sub == null || sub.isEmpty() || shortScreen) {
            statusSub.setVisibility(View.GONE);
        } else {
            statusSub.setVisibility(View.VISIBLE);
            statusSub.setText(sub);
        }
        stateIcon.setImageResource(icon);
        stateIcon.setColorFilter(getResources().getColor(tint, getTheme()));

        boolean connected = wifiRoute || btRoute;
        chip = connected ? "Connected" : "Connect";
        chipIconRes = connected ? R.drawable.ic_link : R.drawable.ic_settings;
        chipLabel.setText(chip);
        chipIcon.setImageResource(chipIconRes);

        // The pad is the remote. It only gives way to a setup card when nothing is configured yet,
        // so a Wi-Fi failure can never make the controls disappear.
        boolean configured = wifiPaired || btBonded || btConnected;
        boolean showPairCard = !configured && !connected;
        pad.setVisibility(showPairCard ? View.GONE : View.VISIBLE);
        pairCard.setVisibility(showPairCard ? View.VISIBLE : View.GONE);
        padSlot.setVisibility(View.VISIBLE);

        setPadEnabled(pad, connected || btConnected || wifiPaired);

        // Mute is a per-transport capability, so it says so instead of looking broken.
        View muteButton = findViewById(R.id.mute);
        if (muteButton != null) {
            boolean muteAvailable = !btRoute;
            muteButton.setEnabled(muteAvailable);
            muteButton.setAlpha(muteAvailable ? 1f : 0.4f);
            muteButton.setContentDescription(muteAvailable
                    ? "Mute projector"
                    : "Mute projector, needs a Wi-Fi connection");
        }

        padHint.setVisibility(sub == null && connected && !shortScreen ? View.VISIBLE : View.GONE);
        padHint.setText(btRoute ? "Bluetooth reaches every app" : "Wi-Fi reaches every app when the helper runs");

        // Transport preference is only offered when Bluetooth really exists on this phone.
        boolean showTransport = btSupported || btConnected || btBonded;
        transportGroup.setVisibility(showTransport ? View.VISIBLE : View.GONE);
        transportNote.setVisibility(showTransport && !shortScreen ? View.VISIBLE : View.GONE);
        if (showTransport) {
            transportAuto.setSelected(core.mode() == InputCore.Mode.AUTO);
            transportWifi.setSelected(core.mode() == InputCore.Mode.WIFI);
            transportBt.setSelected(core.mode() == InputCore.Mode.BT);
            transportAuto.setBackgroundResource(core.mode() == InputCore.Mode.AUTO
                    ? R.drawable.bg_transport_active : R.drawable.bg_transport_item);
            transportWifi.setBackgroundResource(core.mode() == InputCore.Mode.WIFI
                    ? R.drawable.bg_transport_active : R.drawable.bg_transport_item);
            transportBt.setBackgroundResource(core.mode() == InputCore.Mode.BT
                    ? R.drawable.bg_transport_active : R.drawable.bg_transport_item);
            if (!btPermissionGranted()) transportNote.setText("Bluetooth needs permission. Open Settings.");
            else if (!btSupported) transportNote.setText("This phone cannot act as a Bluetooth remote");
            else if (btLastProblem != null && !btConnected) transportNote.setText(btLastProblem);
            else if (core.mode() == InputCore.Mode.WIFI) transportNote.setText("Wi-Fi only");
            else if (core.mode() == InputCore.Mode.BT) transportNote.setText("Bluetooth only");
            else transportNote.setText("Wi-Fi preferred, Bluetooth when it fails");
        }

        // One quiet inline explanation of a completed switch, never a dialog and never a repeat.
        String switchNotice = core.takeNotice();
        if (switchNotice != null) quietNotice(switchNotice);
        if (notice != null && SystemClock.elapsedRealtime() > noticeUntil) {
            notice = null;
        }
        if (notice != null) {
            noticeBar.setVisibility(View.VISIBLE);
            noticeBar.setText(notice);
        } else {
            noticeBar.setVisibility(View.GONE);
        }

        renderBtPanel();
        renderDiagnostics();
    }

    private void setPadEnabled(View padView, boolean enabled) {
        setEnabledRecursive(padView, enabled);
        float alpha = enabled ? 1f : 0.45f;
        padView.setAlpha(alpha);
    }

    private void setEnabledRecursive(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledRecursive(g.getChildAt(i), enabled);
        }
    }

    private void renderBtPanel() {
        if (btStatus == null) return;
        String text;
        if (!btSupported) {
            text = "This phone does not offer a Bluetooth remote";
        } else if (!btPermissionGranted()) {
            text = "Bluetooth permission needed";
        } else if (btConnected) {
            text = "Bluetooth connected" + (btPeerName != null ? " · " + btPeerName : "");
        } else if (btRegistered) {
            text = "Bluetooth ready, not connected";
        } else {
            text = "Bluetooth remote not running";
        }
        if (btLastProblem != null) text = text + "\n" + btLastProblem;
        btStatus.setText(text);
        findViewById(R.id.btPermissionBtn).setVisibility(
                btPermissionGranted() ? View.GONE : View.VISIBLE);
        findViewById(R.id.btScanBtn).setVisibility(btConnected ? View.GONE : View.VISIBLE);
        findViewById(R.id.btPairBtn).setVisibility(btConnected ? View.GONE : View.VISIBLE);
    }

    private void renderDiagnostics() {
        if (diagText == null) return;
        StringBuilder b = new StringBuilder();
        b.append("route      ").append(core.route()).append('\n');
        b.append("mode       ").append(core.mode()).append('\n');
        b.append("wifi       ").append(core.wifiReady() ? "ready" : "not ready")
                .append(ip == null ? "" : "  " + ip + ":" + port).append('\n');
        b.append("wifi flags ").append("a11y=").append(a11yOk).append(" helper=").append(helperOk)
                .append('\n');
        b.append("bluetooth  ").append(btConnected ? "connected" : btRegistered ? "registered"
                : btSupported ? "idle" : "unsupported").append('\n');
        b.append("bt peer    ").append(btPeerName == null ? "-" : btPeerName).append('\n');
        b.append("pressed    ").append(dropped).append(" dropped, ").append(stale).append(" stale\n");
        if (btLastLog != null) b.append("bt log     ").append(btLastLog).append('\n');
        b.append(core.debug());
        diagText.setText(b.toString());
    }

    private void quietNotice(String message) {
        notice = message;
        noticeUntil = SystemClock.elapsedRealtime() + 2600;
        if (noticeBar != null) {
            noticeBar.setVisibility(View.VISIBLE);
            noticeBar.setText(message);
        }
    }

    private void setMode(InputCore.Mode mode) {
        core.setMode(mode);
        prefs.edit().putString("mode", mode.name()).apply();
        render();
    }

    // ------------------------------------------------------------------ sheets

    private void showApps() {
        appsSheet.setVisibility(View.VISIBLE);
        settingsSheet.setVisibility(View.GONE);
        tilesSignature = "";
        renderApps();
    }

    private void showSettings() {
        settingsSheet.setVisibility(View.VISIBLE);
        appsSheet.setVisibility(View.GONE);
        if (jfUrl != null && jfUrl.length() == 0 && !jellyfinUrl.isEmpty()) {
            jfUrl.setText(jellyfinUrl);
        }
        listBtDevices();
        renderBtPanel();
    }

    private void renderApps() {
        if (appList == null) return;
        boolean showAll = allAppsToggle != null && allAppsToggle.isChecked();
        JSONArray source = showAll ? lastAll : lastTiles;
        if (source == null) {
            if (appList.getChildCount() > 0 && !"none".equals(tilesSignature)) {
                tilesSignature = "none";
                appList.removeAllViews();
                TextView t = new TextView(this);
                t.setText(core.wifiReady() ? "No apps yet. The projector is still starting."
                        : "Requires Wi-Fi connection");
                t.setTextColor(getResources().getColor(R.color.dim, getTheme()));
                t.setTextSize(13f);
                appList.addView(t);
            }
            return;
        }
        String signature = source.toString() + (showAll ? "/all" : "/tiles");
        if (signature.equals(tilesSignature)) return;
        tilesSignature = signature;
        appList.removeAllViews();
        for (int i = 0; i < source.length(); i++) {
            JSONObject t = source.optJSONObject(i);
            if (t == null) continue;
            final String id = t.optString("id");
            final String label = t.optString("label", id);
            final boolean risky = t.optBoolean("risky", false);
            final boolean blocked = t.optBoolean("blocked", false);
            Button b = addAppRow(label, blocked);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!core.wifiReady()) {
                        toast("Requires Wi-Fi connection");
                        return;
                    }
                    if (blocked) {
                        toast("Blocked: it switches the projector's input");
                        return;
                    }
                    if (risky) confirmLaunch(label, id);
                    else launch(id);
                }
            });
        }
    }

    private Button addAppRow(String label, boolean blocked) {
        Button b = new Button(this);
        b.setText(blocked ? label + "  (blocked)" : label);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTextColor(getResources().getColor(blocked ? R.color.unavailable : R.color.text, getTheme()));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        appList.addView(b);
        return b;
    }

    private void confirmLaunch(String label, final String id) {
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setMessage(label + " switches the TV's input or wireless mode. While it runs the"
                        + " projector can drop off Wi-Fi; Bluetooth controls keep working.")
                .setPositiveButton("Open anyway", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        launch(id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launch(final String id) {
        final String host = ip;
        final String tok = token;
        if (host == null || tok == null) {
            toast("Requires Wi-Fi connection");
            return;
        }
        bg.execute(new Runnable() {
            public void run() {
                Net.postClassified(host, port, "/api/launch",
                        "{\"id\":\"" + id + "\",\"token\":\"" + tok + "\"}", 1200, 5000);
                ui.post(renderTick);
            }
        });
    }

    // ------------------------------------------------------------------ Wi-Fi setup

    private void scan() {
        connectMsg.setText("Looking for projectors on this Wi-Fi…");
        deviceList.removeAllViews();
        foundLabel.setVisibility(View.GONE);
        bg.execute(new Runnable() {
            public void run() {
                final List<Net.Device> found = Net.discover(2500);
                ui.post(new Runnable() {
                    public void run() {
                        renderDevices(found);
                    }
                });
            }
        });
    }

    private void renderDevices(List<Net.Device> found) {
        deviceList.removeAllViews();
        if (found.isEmpty()) {
            connectMsg.setText("No projector found. Check that both are on the same Wi-Fi,"
                    + " or enter the address below.");
            return;
        }
        foundLabel.setVisibility(View.VISIBLE);
        foundLabel.setText(found.size() == 1 ? "1 projector found" : found.size() + " projectors found");
        connectMsg.setText("Pick the projector, then enter the PIN it is showing.");
        for (final Net.Device d : found) {
            Button b = new Button(this);
            b.setText(d.label());
            b.setAllCaps(false);
            b.setTextSize(14f);
            b.setTextColor(getResources().getColor(R.color.text, getTheme()));
            b.setBackgroundResource(R.drawable.bg_btn_secondary);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(6);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ipInput.setText(d.ip);
                    port = d.port;
                    pinInput.requestFocus();
                    connectMsg.setText("Enter the 6-digit PIN the projector is showing.");
                }
            });
            deviceList.addView(b);
        }
    }

    private void pair() {
        final String addr = ipInput.getText().toString().trim();
        final String pin = pinInput.getText().toString().trim();
        if (TextUtils.isEmpty(addr)) {
            connectMsg.setText("Enter the projector's address first.");
            return;
        }
        if (pin.length() != 6) {
            connectMsg.setText("The PIN is six digits - you entered " + pin.length() + ".");
            return;
        }
        connectMsg.setText("Pairing with " + addr + "…");
        bg.execute(new Runnable() {
            public void run() {
                try {
                    String r = Net.post("http://" + addr + ":" + DEFAULT_PORT + "/api/pair",
                            "{\"pin\":\"" + pin + "\"}", 6000);
                    JSONObject o = new JSONObject(r);
                    if (!o.optBoolean("ok")) {
                        ui.post(new Runnable() {
                            public void run() {
                                connectMsg.setText("That PIN was refused.");
                            }
                        });
                        return;
                    }
                    final String t = o.optString("token", "");
                    ui.post(new Runnable() {
                        public void run() {
                            ip = addr;
                            port = DEFAULT_PORT;
                            token = t;
                            wifiPaired = true;
                            lastWifiProblem = "Connected, checking control…";
                            prefs.edit().putString("ip", ip).putInt("port", port)
                                    .putString("token", token).apply();
                            pinInput.setText("");
                            connectMsg.setText("Paired. The remote is ready.");
                            render();
                        }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            connectMsg.setText("Could not reach " + addr + ". Is it on this Wi-Fi?");
                        }
                    });
                }
            }
        });
    }

    private void forgetWifiPairing() {
        forgetToken();
        wifiPaired = false;
        lastWifiProblem = null;
        prefs.edit().remove("ip").apply();
        ip = null;
        token = null;
        core.onWifiProbe(false, false, SystemClock.elapsedRealtime());
        // Also drop the remembered Bluetooth projector: a bond list contains speakers and headsets
        // too, and a wrong pick must be recoverable without touching Android's Bluetooth settings.
        HidService svc = HidService.instance;
        if (svc != null) {
            svc.forgetProjector();
            btBonded = false;
            btConnected = false;
            btPeerName = null;
            btLastProblem = "Bluetooth projector forgotten";
        }
        connectMsg.setText("Projector forgotten. Pair again, or pick the projector over Bluetooth.");
        render();
    }

    private void forgetToken() {
        token = null;
        prefs.edit().remove("token").apply();
        wifiPaired = false;
    }

    private void saveJellyfin() {
        final String url = jfUrl.getText().toString().trim();
        final String key = jfKey.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            jfMsg.setText("Enter both the server address and an API key.");
            return;
        }
        final String host = ip;
        final String tok = token;
        if (host == null || tok == null) {
            jfMsg.setText("Requires Wi-Fi connection.");
            return;
        }
        bg.execute(new Runnable() {
            public void run() {
                Net.Reply r = Net.postClassified(host, port, "/api/jellyfin",
                        "{\"url\":\"" + url + "\",\"key\":\"" + key + "\",\"token\":\"" + tok + "\"}",
                        1200, 6000);
                final boolean ok = r.ok();
                ui.post(new Runnable() {
                    public void run() {
                        jfMsg.setText(ok ? "Saved. The projector will refresh its posters."
                                : "The projector did not accept that.");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private String nameOrIp() {
        return deviceName != null ? deviceName : (ip != null ? ip : "the projector");
    }

    private JSONObject parse(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateTransportUi() {
        if (transportGroup == null) return;
        transportGroup.setVisibility(btSupported ? View.VISIBLE : View.GONE);
        transportNote.setVisibility(btSupported ? View.VISIBLE : View.GONE);
    }

    private void toast(String message) {
        quietNotice(message);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (appsSheet != null && appsSheet.getVisibility() == View.VISIBLE) {
            appsSheet.setVisibility(View.GONE);
            return;
        }
        if (settingsSheet != null && settingsSheet.getVisibility() == View.VISIBLE) {
            settingsSheet.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }
}
