package com.professor.zerion.android.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

@NotNullByDefault
public class AntiForensics {

	public enum UsbPanicState {
		OFF,
		ARMED,
		FAILED
	}

	public interface PanicAction {

		boolean run();

		void giveUp();
	}

	interface Registrar {
		@Nullable
		Intent register(Context context, BroadcastReceiver receiver,
				IntentFilter filter);
	}

	static final String ACTION_USB_STATE =
			"android.hardware.usb.action.USB_STATE";
	static final String EXTRA_CONNECTED = "connected";
	static final String EXTRA_CONFIGURED = "configured";
	static final String[] DATA_FUNCTIONS = {"adb", "mtp", "ptp"};
	static final int MAX_ATTEMPTS = 3;
	static final long RETRY_DELAY_MS = 2_000L;

	private final Context context;
	private final Registrar registrar;
	private final Executor executor;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable retry = this::retryPanic;
	@Nullable
	private PanicAction usbPanicAction = null;
	private volatile UsbPanicState state = UsbPanicState.OFF;
	private long connection = 0;
	private boolean connected = false;
	private boolean handled = false;
	private boolean inFlight = false;
	private boolean rerun = false;
	private int attempts = 0;
	@Nullable
	private Intent pendingIntent;
	@Nullable
	private Intent dataIntent;
	@Nullable
	private BroadcastReceiver usbReceiver;

	public AntiForensics(Context context) {
		this(context, (c, r, f) -> ContextCompat.registerReceiver(c, r, f,
				ContextCompat.RECEIVER_NOT_EXPORTED),
				Executors.newSingleThreadExecutor());
	}

	@VisibleForTesting
	AntiForensics(Context context, Registrar registrar, Executor executor) {
		this.context = context.getApplicationContext();
		this.registrar = registrar;
		this.executor = executor;
		removeLegacyFingerprint();
	}

	private void removeLegacyFingerprint() {
		try {
			File legacy = new File(context.getFilesDir(), ".device_id");
			if (legacy.exists()) SecureMemory.secureDeleteFile(legacy);
		} catch (Exception ignored) {
		}
	}

	public UsbPanicState getUsbPanicState() {
		return state;
	}

	@VisibleForTesting
	synchronized boolean isHandledForThisConnection() {
		return handled;
	}

	public boolean armUsbPanic(PanicAction panicAction) {
		Intent current;
		synchronized (this) {
			usbPanicAction = panicAction;
			if (usbReceiver == null) {
				BroadcastReceiver r = new BroadcastReceiver() {
					@Override
					public void onReceive(Context c, Intent intent) {
						onUsbState(intent);
					}
				};
				try {
					current = registrar.register(context, r,
							new IntentFilter(ACTION_USB_STATE));
				} catch (RuntimeException e) {
					usbReceiver = null;
					state = UsbPanicState.FAILED;
					return false;
				}
				usbReceiver = r;
			} else {
				current = dataIntent;
			}
			state = UsbPanicState.ARMED;
			if (handled || (!inFlight && pendingIntent == null)) {
				handled = false;
				attempts = 0;
			}
			if (inFlight) rerun = true;
		}
		if (current != null) onUsbState(current);
		return true;
	}

	public synchronized void disarmUsbPanic() {
		usbPanicAction = null;
		state = UsbPanicState.OFF;
		handler.removeCallbacks(retry);
		pendingIntent = null;
		rerun = false;
		BroadcastReceiver r = usbReceiver;
		usbReceiver = null;
		if (r != null) {
			try {
				context.unregisterReceiver(r);
			} catch (Exception ignored) {
			}
		}
	}

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
		PanicAction panic;
		long id;
		synchronized (this) {
			if (!intent.getBooleanExtra(EXTRA_CONNECTED, false)) {
				if (connected) connection++;
				connected = false;
				handled = false;
				rerun = false;
				attempts = 0;
				pendingIntent = null;
				dataIntent = null;
				handler.removeCallbacks(retry);
				return;
			}
			if (!connected) {
				connected = true;
				connection++;
			}
			dataIntent = isDataTransfer(intent) ? intent : null;
			if (dataIntent == null || handled) return;
			if (inFlight) {
				pendingIntent = intent;
				return;
			}
			panic = usbPanicAction;
			if (panic == null || attempts >= MAX_ATTEMPTS) return;
			attempts++;
			inFlight = true;
			id = connection;
			pendingIntent = intent;
			handler.removeCallbacks(retry);
		}
		PanicAction action = panic;
		long attemptConnection = id;
		executor.execute(() -> {
			boolean done;
			try {
				done = action.run();
			} catch (RuntimeException e) {
				done = false;
			}
			boolean result = done;
			handler.post(() -> finished(action, attemptConnection, result));
		});
	}

	private void finished(PanicAction action, long attemptConnection,
			boolean done) {
		boolean giveUp = false;
		synchronized (this) {
			inFlight = false;
			boolean again = rerun;
			rerun = false;
			if (attemptConnection != connection) {
				if (pendingIntent != null) handler.post(retry);
				return;
			}
			if (done && !again) {
				handled = true;
				pendingIntent = null;
				return;
			}
			if (usbPanicAction == null) {
				pendingIntent = null;
				return;
			}
			if (done) {
				attempts = 0;
				handler.post(retry);
				return;
			}
			if (attempts < MAX_ATTEMPTS) {
				handler.postDelayed(retry, RETRY_DELAY_MS);
			} else {
				pendingIntent = null;
				giveUp = true;
			}
		}
		if (giveUp) {
			try {
				action.giveUp();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void retryPanic() {
		Intent intent;
		synchronized (this) {
			intent = pendingIntent;
		}
		onUsbState(intent);
	}
}
