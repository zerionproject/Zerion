package com.professor.zerion.android.security;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.account.AccountWipeCleanup;
import com.professor.zerion.android.backup.RunningCopies;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;

import java.io.File;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@NotNullByDefault
public final class UsbPanic {

	interface ResponderFactory {
		AntiForensics.PanicAction create(boolean wipe);
	}

	private UsbPanic() {
	}

	public static AntiForensics.UsbPanicState armIfConfigured(Context ctx) {
		return armIfConfigured(ctx, getAndroidComponent(ctx));
	}

	public static AntiForensics.UsbPanicState armIfConfigured(Context ctx,
			AndroidComponent component) {
		Context app = ctx.getApplicationContext();
		return armIfConfigured(component.uiPreferences(),
				component.antiForensics(),
				wipe -> new UsbPanicResponder(wipe, new AppSteps(app)));
	}

	static AntiForensics.UsbPanicState armIfConfigured(SharedPreferences uiPrefs,
			AntiForensics antiForensics, ResponderFactory factory) {
		if (!HardenedModeEvaluator.usbPanicArmed(uiPrefs)) {
			antiForensics.disarmUsbPanic();
			return antiForensics.getUsbPanicState();
		}
		boolean wipe = HardenedModeEvaluator.usbPanicWipesAccount(uiPrefs);
		antiForensics.armUsbPanic(factory.create(wipe));
		return antiForensics.getUsbPanicState();
	}

	static final class AppSteps implements UsbPanicResponder.Steps {

		private static final long POLL_MS = 100L;

		private final Context app;

		AppSteps(Context app) {
			this.app = app;
		}

		private AndroidComponent component() {
			return getAndroidComponent(app);
		}

		@Override
		public boolean signedIn() {
			AndroidComponent c = component();
			if (c.accountManager().hasDatabaseKey()) return true;
			LifecycleState s = c.lifecycleManager().getLifecycleState();
			return s.isAfter(LifecycleState.CREATED)
					&& s != LifecycleState.STOPPED;
		}

		@Override
		public void abortCopies() {
			RunningCopies.abortAll();
		}

		@Override
		public void clearMemory() {
			PendingSends.get().clearSilently();
			com.professor.zerion.android.util.SecureClipboard
					.clearOnSignOut(app);
			com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder
					.clear();
		}

		@Override
		public void shredKey() {
			component().accountManager().shredDatabaseKey();
		}

		@Override
		public boolean stopServices(long timeoutMs) {
			LifecycleManager lifecycle = component().lifecycleManager();
			long deadline = SystemClock.elapsedRealtime() + timeoutMs;
			while (starting(lifecycle.getLifecycleState())) {
				if (!sleepUntil(deadline)) return false;
			}
			lifecycle.stopServices();
			try {
				app.stopService(new Intent(app, ZerionService.class));
			} catch (RuntimeException ignored) {
			}
			while (lifecycle.getLifecycleState() == LifecycleState.RUNNING
					|| lifecycle.getLifecycleState()
					== LifecycleState.STOPPING) {
				if (!sleepUntil(deadline)) return false;
			}
			return true;
		}

		private static boolean starting(LifecycleState s) {
			return s.isAfter(LifecycleState.CREATED)
					&& !s.isAfter(LifecycleState.STARTING_SERVICES);
		}

		private static boolean sleepUntil(long deadline) {
			if (SystemClock.elapsedRealtime() >= deadline) return false;
			try {
				Thread.sleep(POLL_MS);
				return true;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		@Override
		public void sweepFiles() {
			CacheSweeper.sweep(app);
			SecureMemory.secureDeleteDir(app.getCacheDir(), 0L);
			File external = app.getExternalCacheDir();
			if (external != null) SecureMemory.secureDeleteDir(external, 0L);
		}

		@Override
		public void wipeAccount() {
			AccountWipeCleanup.wipe(app, component().vaultManager());
			component().accountManager().deleteAccount();
		}

		@Override
		public boolean accountGone() {
			AccountManager accounts = component().accountManager();
			return !accounts.accountExists() && !accounts.isEraseRequested();
		}

		@Override
		public void exit() {
			ActivityManager am = app.getSystemService(ActivityManager.class);
			if (am != null) {
				for (ActivityManager.AppTask task : am.getAppTasks()) {
					try {
						task.finishAndRemoveTask();
					} catch (RuntimeException ignored) {
					}
				}
			}
			if (!((ZerionApplication) app).isInstrumentationTest()) {
				android.os.Process.killProcess(android.os.Process.myPid());
			}
		}
	}
}
