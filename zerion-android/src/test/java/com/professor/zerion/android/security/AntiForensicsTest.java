package com.professor.zerion.android.security;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AND-07: the USB panic decides on the system's USB state broadcast, fires
 * only for a host that configured a data-capable function, once per
 * connection, and never while disarmed.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AntiForensicsTest {

	private static Intent usb(boolean connected, boolean configured,
			String... functions) {
		Intent i = new Intent(AntiForensics.ACTION_USB_STATE);
		i.putExtra(AntiForensics.EXTRA_CONNECTED, connected);
		i.putExtra(AntiForensics.EXTRA_CONFIGURED, configured);
		for (String f : functions) i.putExtra(f, true);
		return i;
	}

	@Test
	public void onlyAConfiguredDataFunctionCountsAsTransfer() {
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "mtp")));
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "adb")));
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "ptp")));
		assertFalse("a charger configures no data function",
				AntiForensics.isDataTransfer(usb(true, true)));
		assertFalse("audio accessory or car head unit",
				AntiForensics.isDataTransfer(usb(true, true, "audio_source")));
		assertFalse("not yet configured by a host",
				AntiForensics.isDataTransfer(usb(true, false, "mtp")));
		assertFalse(AntiForensics.isDataTransfer(usb(false, false, "mtp")));
		assertFalse(AntiForensics.isDataTransfer(null));
		assertFalse(AntiForensics.isDataTransfer(
				new Intent("android.intent.action.BATTERY_CHANGED")));
	}

	@Test
	public void panicFiresOncePerConnectionAndOnlyWhileArmed() {
		AntiForensics af = new AntiForensics(RuntimeEnvironment.getApplication());
		AtomicInteger fired = new AtomicInteger();
		af.onUsbState(usb(true, true, "mtp"));
		assertEquals("disarmed", 0, fired.get());
		af.armUsbPanic(fired::incrementAndGet);
		af.onUsbState(usb(true, true, "mtp"));
		af.onUsbState(usb(true, true, "mtp"));
		assertEquals("once per connection", 1, fired.get());
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "adb"));
		assertEquals("a new connection may fire again", 2, fired.get());
		af.disarmUsbPanic();
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "adb"));
		assertEquals("disarmed again", 2, fired.get());
	}
}
