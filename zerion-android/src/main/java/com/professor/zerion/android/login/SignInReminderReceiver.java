package com.professor.zerion.android.login;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.briarproject.bramble.api.account.AccountManager;
import com.professor.zerion.android.AndroidComponent;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.api.AndroidNotificationManager;

import javax.inject.Inject;
import javax.inject.Provider;

import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_MY_PACKAGE_REPLACED;
import static com.professor.zerion.android.settings.NotificationsFragment.PREF_NOTIFY_SIGN_IN;
import static com.professor.zerion.android.api.AndroidNotificationManager.ACTION_DISMISS_REMINDER;

/**
 * BroadcastReceiver that shows a sign-in reminder notification after boot or package update.
 *
 * IMPORTANT: This receiver uses Provider<T> for heavy dependencies to avoid
 * triggering disk/crypto I/O during DI injection on the main thread.
 * All heavy work happens inside goAsync() on a background thread.
 */
public class SignInReminderReceiver extends BroadcastReceiver {

	// Use Provider<T> to defer resolution until we're on a background thread
	// This avoids StrictMode violations during DI injection
	@Inject
	Provider<AccountManager> accountManagerProvider;
	@Inject
	Provider<AndroidNotificationManager> notificationManagerProvider;
	@Inject
	@AppModule.UiPrefs
	Provider<SharedPreferences> uiPrefsProvider;

	@Override
	public void onReceive(Context ctx, Intent intent) {
		ZerionApplication app = (ZerionApplication) ctx.getApplicationContext();
		AndroidComponent applicationComponent = app.getApplicationComponent();
		applicationComponent.inject(this);

		String action = intent.getAction();
		if (action == null) return;

		// Use goAsync() for ALL actions to ensure heavy work happens off main thread
		// Provider.get() triggers actual dependency resolution which may do disk/crypto I/O
		final PendingResult pendingResult = goAsync();

		new Thread(() -> {
			try {
				// Now safe to resolve providers - we're on a background thread
				AndroidNotificationManager notificationManager = notificationManagerProvider.get();

				if (action.equals(ACTION_DISMISS_REMINDER)) {
					notificationManager.clearSignInNotification();
					return;
				}

				if (action.equals(ACTION_BOOT_COMPLETED) ||
						action.equals(ACTION_MY_PACKAGE_REPLACED)) {
					AccountManager accountManager = accountManagerProvider.get();
					SharedPreferences uiPrefs = uiPrefsProvider.get();

					if (accountManager.accountExists() &&
							!accountManager.hasDatabaseKey()) {
						if (uiPrefs.getBoolean(PREF_NOTIFY_SIGN_IN, true)) {
							notificationManager.showSignInNotification();
						}
					}
				}
			} finally {
				pendingResult.finish();
			}
		}, "SignInReminderReceiver").start();
	}

}
