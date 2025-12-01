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

import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_MY_PACKAGE_REPLACED;
import static com.professor.zerion.android.settings.NotificationsFragment.PREF_NOTIFY_SIGN_IN;
import static com.professor.zerion.android.api.AndroidNotificationManager.ACTION_DISMISS_REMINDER;

public class SignInReminderReceiver extends BroadcastReceiver {

	@Inject
	AccountManager accountManager;
	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	@AppModule.UiPrefs
	SharedPreferences uiPrefs;

	@Override
	public void onReceive(Context ctx, Intent intent) {
		ZerionApplication app = (ZerionApplication) ctx.getApplicationContext();
		AndroidComponent applicationComponent = app.getApplicationComponent();
		applicationComponent.inject(this);

		String action = intent.getAction();
		if (action == null) return;
		if (action.equals(ACTION_BOOT_COMPLETED) ||
				action.equals(ACTION_MY_PACKAGE_REPLACED)) {
			if (accountManager.accountExists() &&
					!accountManager.hasDatabaseKey()) {
				if (uiPrefs.getBoolean(PREF_NOTIFY_SIGN_IN, true)) {
					notificationManager.showSignInNotification();
				}
			}
		} else if (action.equals(ACTION_DISMISS_REMINDER)) {
			notificationManager.clearSignInNotification();
		}
	}

}
