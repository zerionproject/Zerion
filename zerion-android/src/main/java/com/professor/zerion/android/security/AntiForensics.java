package com.professor.zerion.android.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

import javax.annotation.Nullable;

import androidx.core.content.ContextCompat;

/**
 * USB panic for Hardened Mode. The system's USB state broadcast, which
 * every device emits (the legacy sysfs node the old watcher relied on does
 * not exist on configfs devices), reports whether the cable is connected,
 * whether a host configured the device and which functions are active.
 * The panic fires only when a host has configured a data-capable function
 * (adb, MTP or PTP), never for a charger, a car head unit or an audio
 * accessory, and at most once per connection. One receiver is registered
 * for the process; arming and disarming toggle it.
 */
@NotNullByDefault
public class AntiForensics {

	static final String ACTION_USB_STATE =
			"android.hardware.usb.action.USB_STATE";
	static final String EXTRA_CONNECTED = "connected";
	static final String EXTRA_CONFIGURED = "configured";
	static final String[] DATA_FUNCTIONS = {"adb", "mtp", "ptp"};

	private final Context context;
	@Nullable
	private volatile Runnable usbPanicAction = null;
	private volatile boolean firedForThisConnection = false;
	@Nullable
	private BroadcastReceiver usbReceiver;

	public AntiForensics(Context context) {
		this.context = context.getApplicationContext();
		removeLegacyFingerprint();
	}

	/** Older versions kept a device-id hash in plain files; it has no use. */
	private void removeLegacyFingerprint() {
		try {
			File legacy = new File(context.getFilesDir(), ".device_id");
			if (legacy.exists()) SecureMemory.secureDeleteFile(legacy);
		} catch (Exception ignored) {
		}
	}

	public synchronized void armUsbPanic(Runnable panicAction) {
		usbPanicAction = panicAction;
		if (usbReceiver != null) return;
		BroadcastReceiver r = new BroadcastReceiver() {
			@Override
			public void onReceive(Context c, Intent intent) {
				onUsbState(intent);
			}
		};
		try {
			Intent sticky = ContextCompat.registerReceiver(context, r,
					new IntentFilter(ACTION_USB_STATE),
					ContextCompat.RECEIVER_NOT_EXPORTED);
			usbReceiver = r;
			if (sticky != null) onUsbState(sticky);
		} catch (Exception e) {
			usbReceiver = null;
		}
	}

	public synchronized void disarmUsbPanic() {
		usbPanicAction = null;
		BroadcastReceiver r = usbReceiver;
		usbReceiver = null;
		if (r != null) {
			try {
				context.unregisterReceiver(r);
			} catch (Exception ignored) {
			}
		}
	}

	/** Package-visible for tests; the decision is a pure function of the extras. */
	static boolean isDataTransfer(@Nullable Intent intent) {
		if (intent == null || !ACTION_USB_STATE.equals(intent.getAction())) {
			return false;
		}
		if (!intent.getBooleanExtra(EXTRA_CONNECTED, false)) return false;
		if (!intent.getBooleanExtra(EXTRA_CONFIGURED, false)) return false;
		for (String f : DATA_FUNCTIONS) {
			if (intent.getBooleanExtra(f, false)) return true;
		}
		return false;
	}

	void onUsbState(@Nullable Intent intent) {
		if (intent == null) return;
		if (!intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
			firedForThisConnection = false;
			return;
		}
		if (!isDataTransfer(intent) || firedForThisConnection) return;
		Runnable panic = usbPanicAction;
		if (panic == null) return;
		firedForThisConnection = true;
		try {
			panic.run();
		} catch (Exception ignored) {
		}
	}
}
