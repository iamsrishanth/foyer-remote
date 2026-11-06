package com.kaera.foyer.remote;

/**
 * The user's intent, independent of how it is delivered.
 *
 * Only keys that are actually verified on the target projector are listed: D-pad, OK,
 * Back, Home, and the volume trio. Recents is deliberately absent - it is a platform no-op
 * on this projector and there is no HID usage for it, so it must never look functional.
 */
public enum Key {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    HOME,
    VOL_UP,
    VOL_DOWN,
    MUTE;

    /**
     * True when this key can be carried by the Bluetooth HID path.
     *
     * <p>MUTE is deliberately excluded. The report does reach the projector - its kernel raises
     * KEY_MUTE from the consumer-page usage - but the projector's audio stack ignores
     * KEYCODE_VOLUME_MUTE, and injecting that keycode directly with adb changes nothing either. So
     * it is a projector limitation, not a mapping bug, and the phone must not offer it on this
     * transport. Over Wi-Fi it works: /api/volume {"dir":"mute"} really does mute the stream.
     */
    public boolean overHid() {
        return this != MUTE;
    }

    /** True when the Wi-Fi API can carry it. All of these can. */
    public boolean overWifi() {
        return true;
    }
}
