---
name: foyer-remote
colors:
  background: "#0B0E14"
  surface: "#161C27"
  surface-raised: "#1F2937"
  primary: "#5AA9FF"
  on-accent: "#08111C"
  text-primary: "#F2F5FA"
  text-secondary: "#9AA3B2"
---
# Foyer Remote: Design Specification

Target: `com.kaera.foyer.remote`, plain Java + XML, framework widgets only, minSdk 23, `Theme.Material.NoActionBar`.
Audited against `MainActivity.java` (795 lines), `activity_main.xml` (453 lines), `Net.java`, `colors.xml`, `themes.xml`, `drawable/{btn_bg,key_bg,ic_launcher}.xml`, HANDOVER section 8 and 17.

## 1. Design direction

**Calm, low-glare living-room controller.** One intent: the phone is a lamp-lit side table object, not a screen to read. The projector is the bright thing in the room, so this app stays dark, quiet and predictable: a single dominant D-pad that never moves, one status line that tells the truth, and everything config-shaped pushed one deliberate step away. The identity is Foyer's charcoal and blue, unchanged in hue, tightened in hierarchy: charcoal carries surface, blue appears only as *state and action* (accent stroke, ready dot, selected segment) so that when blue appears it means something.

Avoid: bright fills and full-surface flashes on press (glare in a dark room), colour as the only signal, more than one accent hue, equal-weight button rows competing with the pad, decorative dividers and gradients, "Connected" as a decorative badge, any control that looks live when the code cannot act on it, and any layout where the pad is the first thing to shrink or disappear.

## 2. Colour roles

Ratios computed with the WCAG 2.1 relative-luminance formula. Text needs 4.5:1, large text (>=18sp or 14sp bold) and icons/indicators need 3:1.

| Role | Token | Hex | Used on | Ratio | Note |
|---|---|---|---|---|---|
| background | `bg` | `#0B0E14` | root | n/a | unchanged |
| surface | `surface` | `#161C27` | bg | 1.19 | unchanged; pad plate, keys, sheets |
| surface-raised | `surface_high` | `#1F2937` | surface | 1.16 | unchanged; elevated rows, selected segment base |
| surface-pressed | `surface_pressed` | `#26334A` | bg / surface | 1.52 / 1.35 | **new**; pressed fill, replaces the accent flood |
| primary accent | `accent` | `#5AA9FF` | bg / surface / surface-raised | 7.87 / 6.96 / 5.98 | unchanged; all pass |
| on-accent | `on_acc` | `#08111C` | accent | 7.73 | **new**; only text allowed on a solid accent fill |
| text-primary | `text` | `#F2F5FA` | bg / surface / raised | 17.68 / 15.63 / 13.43 | unchanged |
| text-secondary | `dim` | `#9AA3B2` | bg / surface / raised | 7.59 / 6.71 / 5.77 | unchanged; passes at 11sp too |
| text-unavailable | `unavail` | `#8A94A6` | bg / surface / raised | 6.32 / 5.59 / 4.80 | **new**; inert labels, still >=4.5 so no unreadable dead text |
| success / control-ready | `ok` | `#4ADE80` | bg / surface / raised | 11.09 / 9.80 / 8.42 | unchanged |
| warning / degraded | `warn` | `#FFC46B` | bg / surface / raised | 12.29 / 10.87 / 9.34 | **new**; the palette had no partial-failure colour |
| error | `bad` | `#FF8F8F` | bg / surface / raised | 8.81 / 7.79 / 6.69 | unchanged |
| focus ring | `accent` | `#5AA9FF` | bg / surface / raised | 7.87 / 6.96 / 5.98 | 2dp outer ring, 1dp `bg` gap; passes the 3:1 indicator rule |
| divider / hairline | `divider` | `#242E3C` | bg / surface | 1.41 / 1.25 | unchanged value, newly named. Decorative only: structure is carried by 16dp spacing, so this never has to meet 3:1 |

**Changed, and why.** Only one existing value actually fails: `btn_bg.xml` paints the pressed state with a **solid `#5AA9FF` fill**. Against that fill, `@color/text` (#F2F5FA) measures **2.25:1** and `@color/accent` (#5AA9FF, used by Pair/connect and Jellyfin send) measures **1.0:1**, so those labels are unreadable or literally invisible while the finger is down. Fix: pressed = `surface_pressed` fill + 1.5dp `accent` stroke, label unchanged. Same fix in `key_bg.xml` (currently 2dp accent stroke on `surface_high`, which is fine but inconsistent; unify both). Everything else is additive: `on_accent`, `warn`, `unavail`, `divider`, `surface_pressed`. No hue was retuned.

## 3. Typography

Roboto only (`sans-serif` / `sans-serif-medium`). System font, no downloads, no new dependencies. Line height expressed as `android:lineSpacingMultiplier` (`android:lineHeight` is API 28 and minSdk is 23).

| Step | Size | Weight | LH (mult) | Use |
|---|---|---|---|---|
| T1 Title | 22sp | `sans-serif-medium` | 1.25 | projector name in header, sheet titles |
| T2 Subtitle | 17sp | `sans-serif-medium` | 1.30 | section headers, dialog titles, OK key label |
| T3 Body | 15sp | normal | 1.45 | all key labels, list rows, status text |
| T4 Meta | 13sp | normal | 1.40 | supporting line, addresses, footnotes |
| T5 Micro | 11sp | `sans-serif-medium`, caps, `letterSpacing 0.06` | 1.35 | eyebrows (`ON THIS WI-FI`), badge text |

Behaviour under large text. All `sp` scale automatically; every text container uses `wrap_content` height with `minHeight`, never a fixed `layout_height` in dp (today every button is a fixed 52dp or 74dp and clips at 1.3+).
- **font_scale 1.3:** T3 = 19.5sp, T1 = 28.6sp. Header grows to ~72dp, pad does not shrink, one secondary row is dropped first.
- **font_scale 2.0:** T1 = 44sp. Header switches to `maxLines=1` + `ellipsize=end` and T1 is set at runtime to 18sp base (36sp effective) while `statusSub` moves into the status node's `contentDescription`. `autoSizeTextType` is API 26 / AndroidX only, so the step is a plain `if (fontScale >= 1.6)` in Java. Icons and D-pad glyphs are vectors, so they are immune to font_scale and the pad keeps constant visual weight while text grows.

## 4. Spacing and geometry

4dp base grid: `4 8 12 16 20 24 32`. Page margin **16dp** (was 20dp, reclaiming width on a 411dp screen). Gaps: key-to-key **8dp**, row-to-row **8dp**, section **16dp**, header-to-pad **12dp**.

Radii: inputs 10dp, all pressables and keys 14dp (unifies today's 14dp `btn_bg` and 18dp `key_bg`), pad plate and sheets 22dp, transport selector and status chip full pill.

D-pad geometry (the one thing that must never move):
- Key **88dp x 88dp**, gap 8dp, corner 14dp. Footprint `3*88 + 2*8 = 280dp`.
- Plate: 280 + 12dp padding each side = **304dp**, fill `surface`, 1dp `divider` hairline, radius 22dp.
- **OK distinctness:** same 88dp hit box (uniform grid, no asymmetric columns) but a 2dp `accent` ring, `accent` at 22% over `surface` fill, `on_acc`-contrast glyph and a 26sp label. Arrows use a 1dp `divider` stroke, `surface` fill, 24dp `text` chevron. Distinct by ring + fill + glyph weight, never by hue alone.
- Width fallback: `padWidth = min(304dp, parentWidth - 16dp)`; `key = (padWidth - 24 - 16) / 3`. At 360dp wide that is 280dp plate / 80dp keys, still 1.7x the 48dp minimum. Absolute floor 56dp keys (landscape at fontScale 2.0), never below the 48dp minimum.
- Non-hue inactive cue: `key_bg_limited` = 1dp **dashed** `unavail` stroke. Dashed means "cannot act", which survives greyscale and TalkBack.

## 5. Layout and hierarchy

One screen. The pad is never a mode, so the layout does not jump between "pairing" and "remote".

Vertical order, top to bottom:
1. **Header**, 56dp fixed, never scrolls. T1 projector name (`devName`, falls back to `Foyer Remote` before first pair), 24dp `stateIcon`, and the connection/settings action (`connectChip` = "Change" or "Settings"). `Foyer Remote` as a *title* is gone once a projector is known: identity replaces branding, which is what killed the duplicated-heading problem.
2. **Status node**, 44dp fixed. `statusText` (T3, <=5 words) plus `statusSub` (T4, `dim`) on one line. One node, one meaning.
3. **Transport preference**, 44dp fixed. Segmented pill: `Auto | Wi-Fi | Bluetooth`. Separate from status by construction: this is a *preference*, the strip above is *observed reality*. They never share a widget.
4. **`padSlot`**, fixed height 304dp, **not in any ScrollView**. Holds either `pad` (three-by-three GridLayout) or `pairCard` at the same rectangle and height. Because the rectangle is constant, first run, reconnect, degraded and failure all land the pad where the user's thumb already expects it.
5. **`noticeBar`**, 0 or 36dp fixed, only for transient notices (dropped presses, unconfirmed command).
6. **ScrollView, weight 1** (scrolling is the last resort, not the default): `navRow` (Back / Home, 2 across, 56dp), `mediaRow` (Volume down / Mute / Volume up, 3 across, 56dp), `appsRail` (one row of up to 4 tiles + "All apps"), footnotes.

Adaptive plan. Reduce presence first, scroll last; the pad's cell size only changes at the last tier.
- **L0: height >= 700dp and fontScale <= 1.3.** Everything above, no scrolling.
- **L1: 640 to 700dp, or fontScale 1.3 to 1.5.** Drop `appsRail`, shorten footnotes to one line, header to 64dp. Pad stays 88dp keys.
- **L2: below 640dp, or fontScale >= 1.5.** `statusSub` moves into the status node's contentDescription, transport selector collapses to a 44dp icon pill, sections 6 to 8 scroll. Pad stays 280dp / 88dp keys and stays anchored.
- **L3: below 560dp height (i.e. landscape).** Two columns.

Landscape (usable height ~355dp on 1080x2400): header 48dp full width, then a row: left column `pad` at 232dp (64dp keys, gap 8dp) and right column `navRow` + `mediaRow` + "All apps" as 48dp rows with 8dp gaps. Transport selector moves into the header as an icon-only toggle and the full segmented control lives in Settings. At fontScale 2.0 the header becomes 64dp and keys drop to 56dp, still above the 48dp floor. Nothing scrolls in landscape.

**Implementation landmine:** `AndroidManifest.xml` declares `configChanges="orientation|screenSize"`, so the activity is never recreated on rotation and `res/layout-land/` will never be inflated. Either drop `orientation|screenSize` from `configChanges` or drive the landscape swap from `onConfigurationChanged` with two root containers toggled in Java. Do not assume a `-land` resource will work.

## 6. Connection and state model

Two different things wear the word "pair". **Wi-Fi pairing** is a 6-digit PIN on the TV exchanged for an HTTP token (`/api/pair`): it grants a device identity and every `/api` control. **Bluetooth pairing** is a system bond plus an HID link: the phone becomes a keyboard, so it grants keys and nothing else, and there is no channel back from the TV. The UI must never use one word's success to imply the other's capability.

Legend for "stays usable": PAD D-pad/OK, NAV Back/Home, VOL volume/mute, LIB app list and launch, SET settings sheet, PAIR pairing form. "Inert+reason" means the control keeps its shape, uses `key_bg_limited`, and a tap shows the supporting line instead of doing nothing.

| # | State | Status text | Supporting line | Icon | Primary action | Stays usable |
|---|---|---|---|---|---|---|
| W1 | Wi-Fi discovery | Looking for projector | Searches this Wi-Fi only | `ic_wifi_search` | Search again | SET, PAIR (manual) |
| W2 | Devices found | 2 projectors found | Pick the one on your TV | `ic_link_ok` | device row | SET, PAIR |
| W3 | Not found | No projector found | Check it is on, same Wi-Fi | `ic_link_error` | Search again | SET, PAIR |
| W4 | Pairing (PIN sent) | Pairing | Sending the six-digit PIN | `ic_wifi_search` | Cancel | none (fields locked) |
| W5 | PIN refused | PIN not accepted | The PIN rotates; read it again | `ic_link_error` | Try again | PAIR |
| W6 | Reconnecting | Reconnecting | Last used: `<name>` | `ic_wifi_search` | Stop | SET |
| W7 | Control-ready | Connected | `<name>` · Wi-Fi | `ic_link_ok` | SET | PAD NAV VOL LIB SET |
| W8 | Wi-Fi up, keys limited | Keys limited | Wi-Fi connected. Input helper offline | `ic_link_warn` | SET | NAV VOL LIB SET; PAD only inside Foyer |
| W9 | Wi-Fi up, Back/Home off | Back/Home off | Accessibility service not running | `ic_link_warn` | SET | PAD VOL LIB SET |
| W10 | Reachable, backend down | Projector starting | Remote not ready yet, retrying | `ic_link_warn` | Retry now | SET, VOL (will fail with reason) |
| W11 | Unreachable | Projector unreachable | Last seen `<ip>`. Is it on? | `ic_link_error` | Retry | SET, VOL inert+reason |
| W12 | Token rejected (401/403) | Pairing expired | The projector no longer accepts this phone | `ic_link_error` | Pair again | PAIR, SET |
| W13 | Unreadable reply | Unexpected reply | HTTP `<code>` from `<ip>` | `ic_link_warn` | Retry | SET |
| W14 | Command refused | Command refused | `<reason from server>` | `ic_link_error` | dismiss | PAD NAV VOL (state unchanged) |
| W15 | Sent, unconfirmed | Not confirmed | The press may not have landed | `ic_link_warn` | dismiss | all |
| W16 | Presses dropped or stale | Presses dropped | Projector is slow; newest press won | `ic_link_warn` | dismiss | all |
| B1 | BT permission needed | Bluetooth permission needed | Android needs it to find the projector | `ic_bluetooth` | Allow | SET, PAIR |
| B2 | BT permission denied | Bluetooth not allowed | Tap Allow to try again | `ic_bluetooth` | Allow | SET |
| B3 | BT permanently denied | Bluetooth blocked | Enable it in App info | `ic_bluetooth` | Open app settings | SET, PAIR (Wi-Fi) |
| B4 | Bluetooth off | Bluetooth is off | Turn it on to use HID keys | `ic_bluetooth` | Open Bluetooth settings | SET, PAIR (Wi-Fi) |
| B5 | BT searching | Searching Bluetooth | Keep the projector discoverable | `ic_bt_search` | Stop | SET |
| B6 | BT not found | No projector on Bluetooth | Bond it from the projector first | `ic_bt_error` | Search again | SET, PAIR (Wi-Fi) |
| B7 | System bonding prompt | Confirm pairing | Accept on the projector's remote | `ic_bt_prompt` | none (system) | none |
| B8 | Paired, disconnected | Paired, not connected | `<name>`. Keys only | `ic_bt_warn` | Connect | SET |
| B9 | BT connecting | Connecting | Opening the HID link | `ic_bt_search` | Cancel | SET |
| B10 | HID unsupported | HID not supported | This projector ignores key input | `ic_bt_error` | Use Wi-Fi | SET, PAIR (Wi-Fi) |
| B11 | BT reconnecting | Reconnecting Bluetooth | Last link: `<name>` | `ic_bt_search` | Cancel | SET |
| B12 | BT control-ready | Connected by Bluetooth | `<name>` · keys only | `ic_bt_ok` | SET | PAD NAV VOL SET |
| C1 | Neither transport works | No connection | Wi-Fi and Bluetooth are both off | `ic_link_error` | Connect | SET, PAIR |

Rules that keep this honest:
- The **status strip is derived from one state enum**, never from a `setStatus(String, boolean)` call site. One writer, so the icon, the dot and the words cannot disagree (today `applyState()` force-greens the dot and then prints `input helper offline` in the same line).
- Bluetooth rows **B1 to B12 are a contract, not a shipped feature**. Until a `BluetoothTransport` class exists, the Bluetooth segment renders in `unavail` with subtext "Not in this build" and is not selectable; selecting it changes nothing and says so. Nothing in B1 to B12 may be painted as working before the code lands.
- **Wi-Fi-only affordances are labelled, not hidden.** When the active transport is Bluetooth, `appsRail`, the device name from `/api/state`, and Jellyfin carry a `REQUIRES WI-FI` micro badge and open a one-line explanation instead of being removed. Silent disappearance is what makes users think the app is broken.
- Notices (W14 to W16) live in `noticeBar` and are never overwritten by the 5s `/api/state` poll. Today every error is erased within five seconds by the next `applyState()`.

## 7. Capability matrix

| Control | Wi-Fi (HTTP, shipped) | Bluetooth HID (planned) | Shown how | Requires Wi-Fi |
|---|---|---|---|---|
| D-pad + OK | `/api/cmd` `up/down/left/right/ok` | HID arrows + Enter | pad keys 1 to 5 | no |
| Back / Home | `/api/global` `back/home` (needs a11y) | HID Back / Enter | `navRow` | no |
| Volume up/down | `/api/volume` `up/down` | Consumer-control HID | `mediaRow` | no |
| Mute | `/api/volume` `mute` | Consumer-control HID | `mediaRow` | no |
| Recents | `/api/global` `recents`, **platform no-op** | none | Settings, under "Unsupported", with reason | yes |
| App launch | `/api/launch` `{id}` | none | Apps sheet tile | yes |
| App list | `/api/state` `tiles` / `all` | none | Apps sheet, "All apps" | yes |
| Jellyfin config | `/api/jellyfin` `{url,key}` | none | Settings sheet | yes |
| Wallpaper | `/api/wallpaper` exists, **no phone UI** | none | not shown in this build | yes |
| Pairing | 6-digit PIN to token | system bond + HID link | `pairCard` (Wi-Fi) / Settings (BT) | yes / no |

Recents: removed from the primary row. It stays reachable only inside Settings as an unsupported row, with the reason printed, because a big button that does nothing is worse than an absence. Wallpaper: not exposed at all until there is a picker, and this document says so rather than implying it works.

## 8. Accessibility

Content descriptions (exact strings; `contentDescription` on the control, label text unchanged):

| Control | contentDescription |
|---|---|
| D-pad up / down / left / right | "Move up on projector" / "Move down on projector" / "Move left on projector" / "Move right on projector" |
| OK | "Select on projector" |
| Back | "Back on projector" |
| Home | "Foyer home on projector" |
| Volume down / up | "Volume down on projector" / "Volume up on projector" |
| Mute | "Mute projector" |
| Apps | "Open app list" |
| Settings | "Open settings" |
| `connectChip` | "Connect to a projector" |
| `stateIcon` | `importantForAccessibility="no"`, decorative; the status node speaks |
| status node | one node: "Connected. Foyer. Via Wi-Fi. Controls ready." (icon and both text views hidden from a11y) |
| transport Auto / Wi-Fi / Bluetooth | "Connection: automatic" / "Connection: Wi-Fi only" / "Connection: Bluetooth only. Not available in this build" |
| `padHint` | "Controls unavailable. Requires Wi-Fi connection." |
| app tile | "Open `<label>` on the projector" (+ ". May disconnect the remote." for risky tiles) |
| `ipInput` / `pinInput` | label "Projector IP address" / "Projector PIN, six digits" |
| inert key | base description + ", limited" |

Focus order: `connectChip` -> status node -> Auto -> Wi-Fi -> Bluetooth -> **pad ring then centre (up, left, right, down, OK)** -> Back -> Home -> Volume down -> Mute -> Volume up -> Apps -> Settings. OK is last on purpose so a screen-reader user exploring the pad does not trigger an activation on the way around the ring.

Rules: every interactive view >= 48dp x 48dp; D-pad keys 88dp, landscape floor 56dp. State is never colour-only: each connection state carries a distinct icon shape (filled dot / triangle / circle-x / ring) *and* status words *and* a supporting line; the selected transport has a check glyph and a fill change; an inert key has a dashed stroke plus ", limited". Large text is handled per section 3 and must be re-checked at `font_scale` 1.3 and 2.0. Landscape must not clip the pad or the status line.

Touch-target verification (Lead's step, I cannot run adb): `adb shell uiautomator dump /sdcard/ui.xml` then assert every `clickable="true"` node's `bounds` is at least 126 x 126px (48dp at 2.625 density on the 1080px-wide G57) and that each D-pad key is at least 231px (88dp). Cross-check with Accessibility Scanner. Also verify the same at `adb shell settings put system font_scale 2.0` and in landscape.

## 9. Component inventory

Keep: `ic_launcher.xml` (unchanged, it is the app icon). `key_bg.xml` and `btn_bg.xml` keep their roles but both get the pressed-state fix from section 2 and a unified 14dp radius.

New vector drawables, all 24dp / 24 viewport, `fillColor` or 2dp stroke, no new dependencies:
`ic_dpad_up`, `ic_dpad_down`, `ic_dpad_left`, `ic_dpad_right` (chevrons) · `ic_back` (arrow-back) · `ic_home` (house) · `ic_volume_up`, `ic_volume_down` (speaker + wave) · `ic_mute` (speaker + slash) · `ic_apps` (2x2 squares) · `ic_settings` (gear) · `ic_search` (magnifier/refresh) · `ic_wifi` · `ic_wifi_search` · `ic_bluetooth` · `ic_bt_search` · `ic_bt_prompt` · `ic_bt_ok`, `ic_bt_warn`, `ic_bt_error` · `ic_link_ok` (filled dot) · `ic_link_warn` (triangle) · `ic_link_error` (circle-x) · `ic_unlink` (forget projector).

New drawables: `key_bg.xml` (v2, pressed = `surface_pressed` + 1.5dp accent), `key_bg_ok.xml` (accent ring + 22% accent fill), `key_bg_limited.xml` (dashed `unavail` stroke), `btn_bg.xml` (v2, same pressed fix), `chip_bg.xml`, `selector_transport.xml` (RadioButton, checked = `accent` fill + `on_acc` text, `android:button="@null"`), `field_bg.xml` (inputs, 10dp, `bg` fill), `pad_plate_bg.xml`, `sheet_bg.xml` (22dp, `surface`, top-only rounding), `notice_bg.xml`.

Row layouts to inflate from Java instead of building `Button`s by hand: `item_device.xml` (name + address + PAIRED/NEW badge) and `item_app.xml` (icon-less label row at 48dp). Still framework-only.

## 10. Implementation map

`activity_main.xml`, one root `LinearLayout` (`@+id/screen`, vertical, `fitsSystemWindows="true"`, 16dp horizontal padding):

| Block | View group id | Children / ids |
|---|---|---|
| 1 | `header` | `devName` (T1), `stateIcon` (ImageView 24dp), `connectChip` (Button, 44dp min) |
| 2 | `statusRow` | `statusText`, `statusSub` (one a11y node) |
| 3 | `transportGroup` (RadioGroup) | `transportAuto`, `transportWifi`, `transportBt`; `transportNote` |
| 4 | `padSlot` (FrameLayout, fixed 304dp) | `pad` (GridLayout 3x3: `up`,`left`,`ok`,`right`,`down`), `padHint`, `pairCard` (LinearLayout: `ipInput`, `pinInput`, `pairBtn`, `deviceList`, `scanBtn`, `connectMsg`, `foundLabel`) |
| 5 | `noticeBar` | TextView, gone by default |
| 6 | `contentScroll` (ScrollView, weight 1) | `navRow` (`back`,`home`), `mediaRow` (`volDown`,`mute`,`volUp`), `appsRail` (`appListRail`, `appsBtn`), `wifiFootnote` |

Moves behind a **sheet** (framework `Dialog` with a `ScrollView`, not AndroidX bottom-sheet):
- **Apps sheet** (`@+id/appsSheet`): `appList`, plus an "All apps" toggle that switches the source array from `tiles` to `all` and dims `hidden` entries. Replaces the inline "OPEN ON THE PROJECTOR" block. Risky tiles keep the existing `AlertDialog` confirmation, wording unchanged.
- **Settings sheet** (`@+id/settingsSheet`): transport explanation, `btPanel` (`btDeviceList`, `btScanBtn`, `btPairBtn`, `btPermissionBtn`), manual address and PIN (`ipInput`,`pinInput`,`pairBtn`,`scanBtn`,`connectMsg`,`foundLabel`,`deviceList`), Jellyfin (`jfUrl`,`jfKey`,`jfSave`,`jfMsg`), Recents under "Unsupported", `forgetBtn`.

Ids reused as-is: `status` becomes `statusText`, `devName`, `dot` becomes `stateIcon`, `up/down/left/right/ok/back/home/volUp/volDown/mute`, `appList`, `deviceList`, `ipInput`, `pinInput`, `scanBtn`, `pairBtn`, `connectMsg`, `foundLabel`, `jfUrl`, `jfKey`, `jfSave`, `jfMsg`. `recents` survives only inside the settings sheet. New token file `res/values/dimens.xml` for the spacing, radius and key sizes so section 4 is not re-typed per view.

Where the current code will fight this design:
1. `showConnect()` / `showRemote()` are the only two modes and both flip `remoteShown`, which also gates `refreshNow()`. The design needs a persistent pad plus degraded states, so this boolean must become the state enum of section 6, and polling must keep running while degraded (today a 5xx stops the poll and re-pairing looks required).
2. `setStatus(String, boolean)` has ~20 call sites that each own the dot *and* the sentence. One enum writer is required or the green dot will keep contradicting the words (`applyState()` sets the dot green and then appends "input helper offline").
3. `applyState()` writes the device name, the Jellyfin URL, the app tiles and the status in one shot from `/api/state`. On a Bluetooth transport none of that exists, so those regions must be gated on transport rather than on the response.
4. `renderDevices()` and `renderApps()` build raw `Button`s in Java and cannot express a device row with an address and a PAIRED badge, or a tile row. They need `item_device.xml` / `item_app.xml`.
5. The D-pad glyphs are text (U+25B2, U+25C0, U+25B6, U+25BC) recoloured through `setTextColor`. Vectors remove both the font-dependent optical size and the ability of `font_scale` to reshape the pad, but every `findViewById(R.id.up)` cast changes from `Button` to `ImageView`.
6. Fixed `layout_height="52dp"` / `"74dp"` on every control, and `remotePanel` carrying `layout_weight="1"` with the pad *inside* the weighted area, mean the pad is the first thing crushed on a short screen. The pad must leave the weighted/scroll region entirely.
7. No `dimens.xml`, and sizes are inline or `dp(int)`-computed, so the geometry of section 4 has nowhere to live yet.
8. `configChanges` includes `orientation|screenSize`, which makes any `layout-land` resource dead code (see section 5).

### Before to after (the ten reported problems)

| # | Current problem | Fix in this design |
|---|---|---|
| 1 | Two headings: "Foyer Remote" title plus `devName` inside the panel | One header: `devName` is the identity, branding only before the first pair |
| 2 | One overloaded status sentence ("Connected · name · global remote off · input helper offline") | `statusText` <=5 words + `statusSub` one clause + one state icon, from one enum |
| 3 | Fixed 52/74dp controls and three stacked rows crowd small screens; landscape overflows 355dp | 4dp grid, `wrap_content` + `minHeight`, presence tiers L0 to L3, scroll last, two-column landscape |
| 4 | App shortcuts and Jellyfin config sit on the daily remote screen | Apps sheet and Settings sheet, one sheet each |
| 5 | Prominent Recents button that is a platform no-op | Removed from the primary row; appears only in Settings under "Unsupported" with the reason |
| 6 | Whole remote panel disappears when Wi-Fi fails | One screen; the pad persists in every state, controls go inert-with-reason |
| 7 | Connect and remote are mutually exclusive panels, so layout jumps | `padSlot` swaps `pad` and `pairCard` at the same fixed rectangle |
| 8 | Pressed state floods the key with solid accent (label 2.25:1, accent label 1.0:1) | `surface_pressed` fill + 1.5dp accent stroke; label contrast 11.61:1 |
| 9 | D-pad arrows are Unicode text that reflow with `font_scale` | 24dp vectors, pad geometry immune to font scale |
| 10 | Errors are overwritten by the next 5s state poll | `noticeBar` holds notices until resolved or dismissed; the poll never writes it |

Palette summary for review: `bg #0B0E14`, `surface #161C27`, `surface-raised #1F2937`, `surface-pressed #26334A`, `accent #5AA9FF`, `on-accent #08111C`, `text #F2F5FA`, `dim #9AA3B2`, `unavailable #8A94A6`, `ok #4ADE80`, `warn #FFC46B`, `bad #FF8F8F`, `divider #242E3C`. All text roles measure 4.80:1 or better on every surface they sit on; the only value changed from the shipped file is the pressed fill inside `btn_bg.xml` and `key_bg.xml`, because the old one failed at 2.25:1 and 1.0:1.

---

## Addendum: what was verified on the actual devices (27 Oct 2026)

This section records where the shipped build departs from the specification above, and why. The
specification was written before the Bluetooth feasibility probe; these are measured results, not
preferences.

**Transport.** The redesigned screen shows an Auto / Wi-Fi / Bluetooth preference selector above
the pad. It is visible only when the phone really offers a Bluetooth HID device role, which this
reference phone does.

**Capability correction: mute.** The spec's matrix implied mute on both transports. On the projector
it is Wi-Fi only:

- Wi-Fi POST /api/volume {"dir":"mute"} -> {"ok":true} and dumpsys audio reports Muted: true.
- Bluetooth consumer usage 0x00E2 -> the projector's kernel raises KEY_MUTE, but Muted never
  changes; injecting KEYCODE_VOLUME_MUTE directly with adb changes nothing either.

So the mute key is disabled whenever Bluetooth is the active route, and its accessibility label
becomes "Mute projector, needs a Wi-Fi connection". It is not hidden, because a control that
vanishes is more confusing than one that explains itself.

**Layout deviations from the spec, all forced by the real widget set (no AndroidX):**

1. The nav/volume/bottom rows live in one un-ID'd weight-1 ScrollView so a notice appearing above
   them can never clip ottomRow. padSlot still sits outside every scroll container, so the pad
   rectangle is stable.
2. pad_gap is the visible 8dp gap; per-key margins use pad_key_margin (4dp) so adjacent margins
   sum to 8dp rather than 16dp. Base pad column 260dp inside the 264dp slot; alues-h700dp scales
   keys to 88dp / OK 108dp inside a 320dp slot.
3. 	ransportBt and ecents stay enabled and are styled unavailable rather than disabled, so a tap
   produces the explaining line instead of a dead control. The transport selection is gated in Java.

**States confirmed with screenshots on the device:** Wi-Fi ready ("Controlling via Wi-Fi" / "Works
inside Foyer only"), Bluetooth ready ("Controlling via Bluetooth" / peer name), automatic fallback
after Foyer was force-stopped, and automatic recovery back to Wi-Fi after the dwell window.

**Accessibility labels actually shipped** (from uiautomator dump, confirming the spec's strings):
"Move up/down/left/right on projector", "Select on projector", "Back on projector", "Foyer home on
projector", "Volume up/down on projector", "Mute projector", "Open app list", "Open settings",
"Connection: automatic / Wi-Fi only / Bluetooth only", "Connect to a projector".
