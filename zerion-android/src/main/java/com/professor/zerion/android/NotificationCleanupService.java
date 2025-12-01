package com.professor.zerion.android;

import android.app.IntentService;
import android.content.Intent;
import android.net.Uri;

import com.professor.zerion.android.api.AndroidNotificationManager;

import javax.inject.Inject;

import androidx.annotation.Nullable;

import static com.professor.zerion.android.navdrawer.NavDrawerActivity.CONTACT_ADDED_URI;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.CONTACT_URI;
import static com.professor.zerion.android.navdrawer.NavDrawerActivity.GROUP_URI;

public class NotificationCleanupService extends IntentService {

	private static final String TAG =
			NotificationCleanupService.class.getName();

	@Inject
	AndroidNotificationManager notificationManager;

	public NotificationCleanupService() {
		super(TAG);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		AndroidComponent applicationComponent =
				((ZerionApplication) getApplication()).getApplicationComponent();
		applicationComponent.inject(this);
	}

	@Override
	protected void onHandleIntent(@Nullable Intent i) {
		if (i == null || i.getData() == null) return;
		Uri uri = i.getData();
		if (uri.equals(CONTACT_URI)) {
			notificationManager.clearAllContactNotifications();
		} else if (uri.equals(GROUP_URI)) {
			notificationManager.clearAllGroupMessageNotifications();
		} else if (uri.equals(CONTACT_ADDED_URI)) {
			notificationManager.clearAllContactAddedNotifications();
		}
	}
}
