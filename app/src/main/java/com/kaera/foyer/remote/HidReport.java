package com.kaera.foyer.remote;

/**
 * HID report layout for the phone remote, kept free of Android types so it can be unit tested.
 *
 * <p>One descriptor carries two report ids: a boot-style keyboard (report 1) and a consumer
 * control (report 2). D-pad and OK ride on keyboard usages because Android TV turns those into
 * focus moves and a click; Back, Home, volume and mute only work from the consumer page of this
 * particular projector, which is why both collections exist in one descriptor.
 *
 * <p>Everything here is copied from the probe build that the projector accepted. Treat the
 * descriptor bytes as device-verified data: changing one requires a fresh probe, because the
 * host caches the report descriptor at pairing time.
 */
public final class HidReport {

    public static final int REPORT_ID_KEYBOARD = 1;
    public static final int REPORT_ID_CONSUMER = 2;

    private static final int KEYBOARD_REPORT_LENGTH = 8;
    private static final int CONSUMER_REPORT_LENGTH = 2;

    // Keyboard usages (Keyboard/Keypad page 0x07).
    private static final int KEY_UP = 0x52;
    private static final int KEY_DOWN = 0x51;
    private static final int KEY_LEFT = 0x50;
    private static final int KEY_RIGHT = 0x4F;
    /** There is no HID usage for DPAD_CENTER, so OK has to ride on Enter. */
    private static final int KEY_ENTER = 0x28;

    // Consumer usages (Consumer page 0x0C).
    private static final int CONSUMER_VOL_UP = 0x00E9;
    private static final int CONSUMER_VOL_DOWN = 0x00EA;
    private static final int CONSUMER_MUTE = 0x00E2;
    private static final int CONSUMER_AC_BACK = 0x0224;
    private static final int CONSUMER_AC_HOME = 0x0223;

    /**
     * Composite report descriptor, byte for byte from the working probe.
     *
     * <p>Report 1 is a keyboard with a modifier byte, a reserved byte and six key slots. Report 2
     * is a single 16 bit absolute consumer usage, wide enough for AC Back (0x0224) and AC Home
     * (0x0223); a one byte usage would silently truncate both.
     */
    private static final byte[] DESCRIPTOR = new byte[]{
            // Keyboard, report id 1.
            (byte) 0x05, (byte) 0x01,               // Usage Page (Generic Desktop)
            (byte) 0x09, (byte) 0x06,               // Usage (Keyboard)
            (byte) 0xA1, (byte) 0x01,               // Collection (Application)
            (byte) 0x85, (byte) REPORT_ID_KEYBOARD, //   Report ID
            (byte) 0x05, (byte) 0x07,               //   Usage Page (Keyboard/Keypad)
            (byte) 0x19, (byte) 0xE0,               //   Usage Minimum (Left Control)
            (byte) 0x29, (byte) 0xE7,               //   Usage Maximum (Right GUI)
            (byte) 0x15, (byte) 0x00,               //   Logical Minimum (0)
            (byte) 0x25, (byte) 0x01,               //   Logical Maximum (1)
            (byte) 0x75, (byte) 0x01,               //   Report Size (1)
            (byte) 0x95, (byte) 0x08,               //   Report Count (8)
            (byte) 0x81, (byte) 0x02,               //   Input (Data,Var,Abs) -> modifier byte
            (byte) 0x95, (byte) 0x01,               //   Report Count (1)
            (byte) 0x75, (byte) 0x08,               //   Report Size (8)
            (byte) 0x81, (byte) 0x01,               //   Input (Const)        -> reserved byte
            (byte) 0x95, (byte) 0x06,               //   Report Count (6)
            (byte) 0x75, (byte) 0x08,               //   Report Size (8)
            (byte) 0x15, (byte) 0x00,               //   Logical Minimum (0)
            (byte) 0x25, (byte) 0x65,               //   Logical Maximum (101)
            (byte) 0x05, (byte) 0x07,               //   Usage Page (Keyboard/Keypad)
            (byte) 0x19, (byte) 0x00,               //   Usage Minimum (0)
            (byte) 0x29, (byte) 0x65,               //   Usage Maximum (101)
            (byte) 0x81, (byte) 0x00,               //   Input (Data,Array)    -> 6 key slots
            (byte) 0xC0,                            // End Collection

            // Consumer control, report id 2.
            (byte) 0x05, (byte) 0x0C,               // Usage Page (Consumer)
            (byte) 0x09, (byte) 0x01,               // Usage (Consumer Control)
            (byte) 0xA1, (byte) 0x01,               // Collection (Application)
            (byte) 0x85, (byte) REPORT_ID_CONSUMER, //   Report ID
            (byte) 0x15, (byte) 0x00,               //   Logical Minimum (0)
            (byte) 0x26, (byte) 0xFF, (byte) 0x03,  //   Logical Maximum (1023)
            (byte) 0x19, (byte) 0x00,               //   Usage Minimum (0)
            (byte) 0x2A, (byte) 0xFF, (byte) 0x03,  //   Usage Maximum (1023)
            (byte) 0x75, (byte) 0x10,               //   Report Size (16)
            (byte) 0x95, (byte) 0x01,               //   Report Count (1)
            (byte) 0x81, (byte) 0x00,               //   Input (Data,Array)
            (byte) 0xC0                             // End Collection
    };

    private HidReport() {
    }

    /** The verified composite descriptor. A copy is returned so callers cannot corrupt it. */
    public static byte[] descriptor() {
        return DESCRIPTOR.clone();
    }

    /**
     * The press and release frames for one key, or null when the key has no verified usage.
     *
     * <p>Release frames are the same length as the press frame and all zero: a HID host keeps a
     * key logically held until it sees a report that no longer lists it.
     */
    public static Frame frameFor(final Key key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case UP:
                return keyboard(key, KEY_UP);
            case DOWN:
                return keyboard(key, KEY_DOWN);
            case LEFT:
                return keyboard(key, KEY_LEFT);
            case RIGHT:
                return keyboard(key, KEY_RIGHT);
            case OK:
                return keyboard(key, KEY_ENTER);
            case BACK:
                return consumer(key, CONSUMER_AC_BACK);
            case HOME:
                return consumer(key, CONSUMER_AC_HOME);
            case VOL_UP:
                return consumer(key, CONSUMER_VOL_UP);
            case VOL_DOWN:
                return consumer(key, CONSUMER_VOL_DOWN);
            case MUTE:
                return consumer(key, CONSUMER_MUTE);
            default:
                return null;
        }
    }

    /**
     * All-zero report for a report id, which a HID host reads as "no key is down".
     * Unknown ids return an empty array so a caller can never send a wrongly sized report.
     */
    public static byte[] releaseFor(final int reportId) {
        if (reportId == REPORT_ID_KEYBOARD) {
            return new byte[KEYBOARD_REPORT_LENGTH];
        }
        if (reportId == REPORT_ID_CONSUMER) {
            return new byte[CONSUMER_REPORT_LENGTH];
        }
        return new byte[0];
    }

    private static Frame keyboard(final Key key, final int usage) {
        final byte[] press = new byte[KEYBOARD_REPORT_LENGTH];
        // Byte 0 is the modifier mask and byte 1 is reserved, so the first key slot is byte 2.
        press[2] = (byte) usage;
        return new Frame(REPORT_ID_KEYBOARD, press, releaseFor(REPORT_ID_KEYBOARD),
                key.name() + " keyboard 0x" + Integer.toHexString(usage));
    }

    private static Frame consumer(final Key key, final int usage) {
        final byte[] press = new byte[]{(byte) (usage & 0xFF), (byte) ((usage >> 8) & 0xFF)};
        return new Frame(REPORT_ID_CONSUMER, press, releaseFor(REPORT_ID_CONSUMER),
                key.name() + " consumer 0x" + Integer.toHexString(usage));
    }

    /** One key press expressed as the two reports that must be handed to the stack. */
    public static final class Frame {

        public final int reportId;
        public final byte[] press;
        public final byte[] release;
        public final String label;

        private Frame(final int reportId, final byte[] press, final byte[] release,
                final String label) {
            this.reportId = reportId;
            this.press = press;
            this.release = release;
            this.label = label;
        }
    }
}
