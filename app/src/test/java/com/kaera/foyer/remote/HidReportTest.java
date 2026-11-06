package com.kaera.foyer.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Wire format tests for the HID mapping. They run on a plain JVM, so a change to a usage value is
 * caught before it reaches a phone and silently stops a button working.
 */
public class HidReportTest {

    /** The descriptor the projector accepted during the probe. Any edit needs a re-probe. */
    private static final int DESCRIPTOR_LENGTH = 72;

    @Test
    public void descriptorHasTheVerifiedLength() {
        byte[] d = HidReport.descriptor();
        assertEquals(DESCRIPTOR_LENGTH, d.length);
        assertEquals(0x05, d[0] & 0xFF);              // Usage Page (Generic Desktop)
        assertEquals(0x06, d[3] & 0xFF);              // Usage (Keyboard)
        assertEquals(0x85, d[6] & 0xFF);              // Report ID
        assertEquals(HidReport.REPORT_ID_KEYBOARD, d[7] & 0xFF);
        assertEquals(0xC0, d[d.length - 1] & 0xFF);   // End Collection
        assertTrue(hasReportId(d, HidReport.REPORT_ID_CONSUMER));
    }

    @Test
    public void everyKeyMapsToItsReportId() {
        for (Key k : Key.values()) {
            HidReport.Frame f = HidReport.frameFor(k);
            assertNotNull("no frame for " + k, f);
            assertEquals("report id for " + k, expectedReportId(k), f.reportId);
            assertEquals("press length for " + k, releaseLength(k), f.press.length);
            assertNotNull("no label for " + k, f.label);
            assertFalse("empty label for " + k, f.label.isEmpty());
        }
    }

    @Test
    public void dpadUsesTheKeyboardArrowKeys() {
        assertEquals(HidReport.REPORT_ID_KEYBOARD, HidReport.frameFor(Key.UP).reportId);
        assertEquals(0x52, HidReport.frameFor(Key.UP).press[2] & 0xFF);
        assertEquals(0x51, HidReport.frameFor(Key.DOWN).press[2] & 0xFF);
        assertEquals(0x50, HidReport.frameFor(Key.LEFT).press[2] & 0xFF);
        assertEquals(0x4F, HidReport.frameFor(Key.RIGHT).press[2] & 0xFF);
        byte[] up = HidReport.frameFor(Key.UP).press;
        assertEquals(0, up[0]);                        // no modifier
        assertEquals(0, up[1]);                        // reserved
        for (int i = 3; i < up.length; i++) {
            assertEquals("slot " + i + " must stay empty", 0, up[i]);
        }
    }

    @Test
    public void okRidesOnEnter() {
        HidReport.Frame ok = HidReport.frameFor(Key.OK);
        assertEquals(HidReport.REPORT_ID_KEYBOARD, ok.reportId);
        assertEquals(0x28, ok.press[2] & 0xFF);
    }

    @Test
    public void backAndHomeUseTheConsumerPage() {
        assertArrayEquals(new byte[]{0x24, 0x02}, HidReport.frameFor(Key.BACK).press);
        assertArrayEquals(new byte[]{0x23, 0x02}, HidReport.frameFor(Key.HOME).press);
        assertEquals(HidReport.REPORT_ID_CONSUMER, HidReport.frameFor(Key.HOME).reportId);
    }

    @Test
    public void volumeAndMuteUseTheConsumerPage() {
        assertArrayEquals(new byte[]{(byte) 0xE9, 0x00}, HidReport.frameFor(Key.VOL_UP).press);
        assertArrayEquals(new byte[]{(byte) 0xEA, 0x00}, HidReport.frameFor(Key.VOL_DOWN).press);
        assertArrayEquals(new byte[]{(byte) 0xE2, 0x00}, HidReport.frameFor(Key.MUTE).press);
    }

    @Test
    public void everyReleaseIsAllZeroOfTheRightLength() {
        for (Key k : Key.values()) {
            HidReport.Frame f = HidReport.frameFor(k);
            assertEquals("release length for " + k, releaseLength(k), f.release.length);
            assertArrayEquals("release for " + k, new byte[releaseLength(k)], f.release);
        }
    }

    @Test
    public void nullKeyHasNoFrame() {
        assertNull(HidReport.frameFor(null));
    }

    private static boolean hasReportId(byte[] descriptor, int id) {
        for (int i = 0; i + 1 < descriptor.length; i++) {
            if ((descriptor[i] & 0xFF) == 0x85 && (descriptor[i + 1] & 0xFF) == id) return true;
        }
        return false;
    }

    private static int expectedReportId(Key k) {
        switch (k) {
            case BACK:
            case HOME:
            case VOL_UP:
            case VOL_DOWN:
            case MUTE:
                return HidReport.REPORT_ID_CONSUMER;
            default:
                return HidReport.REPORT_ID_KEYBOARD;
        }
    }

    private static int releaseLength(Key k) {
        return expectedReportId(k) == HidReport.REPORT_ID_KEYBOARD ? 8 : 2;
    }
}
