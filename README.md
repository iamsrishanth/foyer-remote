# Foyer Remote

The phone remote for the Foyer projector launcher. Framework-only Java and XML, no AndroidX, no
Compose, no third-party libraries, no styling dependencies.

```
com.kaera.foyer.remote  minSdk 23  targetSdk 31  compileSdk 35
```

## Two transports, one set of buttons

| | Wi-Fi API | Bluetooth HID |
|---|---|---|
| How it works | HTTP to Foyer's `LauncherServer` on the projector, which hands keys to a shell-uid input helper | The phone registers as a Bluetooth HID keyboard/consumer device; the projector's own Bluetooth stack delivers the keys |
| Depends on Foyer's process | **Yes.** When the vendor RAM manager kills Foyer during playback, the endpoint and the helper route are gone | **No.** Input goes phone -> projector BT stack -> kernel -> whatever app is in front |
| Needs projector-side setup | Pairing PIN once | Bluetooth bond once |
| D-pad and OK | yes | yes |
| Back and Home | yes (needs Foyer's accessibility service) | yes (consumer usages 0x0224 / 0x0223) |
| Volume up/down | yes | yes (consumer 0x00E9 / 0x00EA) |
| Mute | yes (`/api/volume`) | **no** - see below |
| App list, app launch, Jellyfin, wallpaper | yes | not available (there is no return channel) |

**Mute is Wi-Fi only.** Over Bluetooth the projector's kernel does raise `KEY_MUTE` from the
consumer-page report, but its audio stack ignores `KEYCODE_VOLUME_MUTE`; injecting that keycode with
adb changes nothing either. So it is a projector limitation, not a mapping bug. The button is
disabled with an explaining accessibility label whenever Bluetooth is the active route.

**Recents is not offered.** `/api/global recents` is a platform no-op on this projector and there is
no HID usage for it. It lives in Settings under "Not supported", not in the control row.

## Choosing a transport

`Auto / Wi-Fi / Bluetooth` in the header. The preference is stored; the active route is computed.

- **Auto** uses Wi-Fi while it is demonstrably healthy and Bluetooth otherwise.
- **Wi-Fi** never uses Bluetooth, and shows "Wi-Fi connected, controls unavailable" with a switch
  option when the API is up but cannot route input.
- **Bluetooth** uses only the HID link. Wi-Fi may still serve the app list and Jellyfin, and the
  status line says which transport is carrying the buttons.

The switching rules (`InputCore`) are deliberately boring:

- A press is dispatched on exactly one transport, chosen once at press time.
- A timeout or an `unknown` reply is **never** replayed. Only "provably not sent" degrades a route.
- Two unconfirmed presses in a row count as a degraded route; one does not.
- Wi-Fi is only reclaimed after two consecutive successful probes, a 12 second dwell, and with no
  press in flight, so a lucky reply cannot yank navigation away mid-tap.
- A completed switch is explained once, inline, and never with a dialog.

## Setup

### Wi-Fi (rich features)

1. On the projector, open the Pair phone overlay (top-right QR button) to show the 6-digit PIN.
2. In the phone's Settings sheet, tap **Find projector**, pick it, type the PIN, tap **Pair with PIN**.
3. If the projector cannot be found, type its address (for example `192.168.1.50`) directly.

The PIN grants an HTTP token; it is not a Bluetooth bond. Losing the token does not touch the
Bluetooth link, and losing the Bluetooth link does not touch the token.

### Bluetooth (fallback)

1. Settings -> Bluetooth fallback -> **Allow Bluetooth access** (Android 12+ runtime permissions).
2. **Make this phone visible** -> accept the system prompt.
3. On the projector: Settings -> Bluetooth -> pair with the phone (it appears under this phone's
   own Bluetooth name).
4. The phone then lists the projector under **Look for projector**; tap it if it does not connect by
   itself.

The projector sees the phone as `<phone name> Keyboard` and `<phone name> Consumer Control` in
`dumpsys input`. That is the acceptance signal; a registered HID app alone proves nothing.

## States the UI can show

Wi-Fi: searching, not found, pairing, PIN refused, connected, "Works inside Foyer only" (helper
offline), "Back and Home unavailable" (accessibility off), "Wi-Fi connected, controls unavailable"
(launcher not running), unreachable, pairing expired.

Bluetooth: permission needed, denied, blocked in system settings, off/unsupported, ready but not
connected, connecting, connected, registration lost, reconnecting.

Both routes unavailable: "No control connection" with the reason and one action.

The D-pad never disappears. It gives way to a setup card only when nothing at all is configured yet,
so a Wi-Fi failure cannot remove the remote from the screen.

## Build

On the build machine:

```
cd ~/foyer-remote && ./gradlew assembleDebug testDebugUnitTest --no-daemon
```

`testDebugUnitTest` runs 25 plain-JUnit tests: 17 for the routing rules (`InputCoreTest`) and 8 for
the HID report bytes (`HidReportTest`). Nothing else is tested automatically; device behaviour is
verified by hand on the target projector.

## Files

```
MainActivity.java   state machine, all UI, Wi-Fi pairing, diagnostics
InputCore.java      transport selection and hysteresis (pure Java, unit tested)
Key.java            user intent + per-transport capability
Net.java            HTTP + UDP helpers; postClassified() separates "not sent" from "unknown"
HidReport.java      the HID report descriptor and per-key frames (pure Java, unit tested)
BtHid.java          BluetoothHidDevice wrapper
HidService.java     foreground service that owns the HID registration
DESIGN.md           the design specification this UI implements
```

## License

MIT — see [LICENSE](LICENSE).
