package com.professor.zerion.android.controller;

import android.app.Activity;
import android.content.Intent;
import android.os.IBinder;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.ZerionService.ZerionServiceConnection;
import com.professor.zerion.android.account.AccountWipeCleanup;
import com.professor.zerion.android.controller.handler.ResultHandler;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.vault.VaultManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.CallSuper;

import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;
import static org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState.STARTING_SERVICES;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;

@NotNullByDefault
public class ZerionControllerImpl implements ZerionController {

	public static final String DOZE_ASK_AGAIN = "dozeAskAgain";

	private final ZerionServiceConnection serviceConnection;
	private final AccountManager accountManager;
	private final LifecycleManager lifecycleManager;
	private final Executor databaseExecutor;
	private final SettingsManager settingsManager;
	private final DozeWatchdog dozeWatchdog;
	private final AndroidWakeLockManager wakeLockManager;
	private final Activity activity;
	private final VaultManager vaultManager;

	private boolean bound = false;

	@Inject
	ZerionControllerImpl(ZerionServiceConnection serviceConnection,
			AccountManager accountManager,
			LifecycleManager lifecycleManager,
			@DatabaseExecutor Executor databaseExecutor,
			SettingsManager settingsManager,
			DozeWatchdog dozeWatchdog,
			AndroidWakeLockManager wakeLockManager,
			VaultManager vaultManager,
			Activity activity) {
		this.serviceConnection = serviceConnection;
		this.accountManager = accountManager;
		this.lifecycleManager = lifecycleManager;
		this.databaseExecutor = databaseExecutor;
		this.settingsManager = settingsManager;
		this.dozeWatchdog = dozeWatchdog;
		this.wakeLockManager = wakeLockManager;
		this.vaultManager = vaultManager;
		this.activity = activity;
	}

	@Override
	@CallSuper
	public void onActivityCreate(Activity activity) {
		if (accountManager.hasDatabaseKey()) startAndBindService();
	}

	@Override
	public void onActivityStart() {
	}

	@Override
	public void onActivityStop() {
	}

	@Override
	@CallSuper
	public void onActivityDestroy() {
		unbindService();
	}

	@Override
	public void startAndBindService() {
		activity.startService(new Intent(activity, ZerionService.class));
		bound = activity.bindService(new Intent(activity, ZerionService.class),
				serviceConnection, 0);
	}

	@Override
	public boolean accountSignedIn() {
		return accountManager.hasDatabaseKey() &&
				lifecycleManager.getLifecycleState().isAfter(STARTING_SERVICES);
	}

	@Override
	public void hasDozed(ResultHandler<Boolean> handler) {
		ZerionApplication app = (ZerionApplication) activity.getApplication();
		if (app.isInstrumentationTest() || !dozeWatchdog.getAndResetDozeFlag()
				|| !needsDozeWhitelisting(activity)) {
			handler.onResult(false);
			return;
		}
		databaseExecutor.execute(() -> {
			try {
				Settings settings =
						settingsManager.getSettings(SETTINGS_NAMESPACE);
				boolean ask = settings.getBoolean(DOZE_ASK_AGAIN, true);
				handler.onResult(ask);
			} catch (DbException e) {

			}
		});
	}

	@Override
	public void doNotAskAgainForDozeWhiteListing() {
		databaseExecutor.execute(() -> {
			try {
				Settings settings = new Settings();
				settings.putBoolean(DOZE_ASK_AGAIN, false);
				settingsManager.mergeSettings(settings, SETTINGS_NAMESPACE);
			} catch (DbException e) {

			}
		});
	}

	@Override
	public void signOut(ResultHandler<Void> handler, boolean deleteAccount) {
		wakeLockManager.executeWakefully(() -> {
			try {
				IBinder binder = serviceConnection.waitForBinder();
				ZerionService service =
						((ZerionService.ZerionBinder) binder).getService();
				service.waitForStartup();
				service.shutdown(true);
				service.waitForShutdown();
			} catch (InterruptedException e) {
			} finally {
				try {
					new com.professor.zerion.android.security
							.AntiForensics(activity)
							.wipeCachesOnLogout();
				} catch (Exception ignored) {
				}
				if (deleteAccount) fullAccountWipe();
			}
			handler.onResult(null);
		}, "SignOut");
	}

	public void armUsbPanicIfConfigured(
			com.professor.zerion.android.security.AntiForensics
					antiForensics,
			android.content.SharedPreferences uiPrefs) {
		if (!com.professor.zerion.android.security
				.HardenedModeEvaluator.usbPanicArmed(uiPrefs)) {
			antiForensics.disarmUsbPanic();
			return;
		}
		boolean alsoWipe = com.professor.zerion.android.security
				.HardenedModeEvaluator.usbPanicWipesAccount(uiPrefs);
		antiForensics.armUsbPanic(() ->
				signOut(result -> {}, alsoWipe));
	}

	@Override
	public void deleteAccount() {
		fullAccountWipe();
	}

	private void fullAccountWipe() {
		AccountWipeCleanup.wipe(activity, vaultManager);
		accountManager.deleteAccount();
	}

	private void unbindService() {
		if (bound) activity.unbindService(serviceConnection);
	}

}
