package com.professor.zerion.android.settings;

import android.content.Context;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.widget.Toast;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import com.professor.zerion.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static android.widget.Toast.LENGTH_SHORT;
import static com.professor.zerion.android.settings.SettingsFragment.SETTINGS_NAMESPACE;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_GROUP;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_PRIVATE;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_RINGTONE_NAME;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_RINGTONE_URI;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_SOUND;
import static com.professor.zerion.android.api.AndroidNotificationManager.PREF_NOTIFY_VIBRATION;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
class NotificationsManager {

	private final Context ctx;
	private final SettingsManager settingsManager;
	private final Executor dbExecutor;

	private final MutableLiveData<Boolean> notifyPrivateMessages =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> notifyGroupMessages =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> notifyVibration =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> notifySound =
			new MutableLiveData<>();

	private volatile String ringtoneName, ringtoneUri;

	NotificationsManager(Context ctx,
			SettingsManager settingsManager,
			Executor dbExecutor) {
		this.ctx = ctx;
		this.settingsManager = settingsManager;
		this.dbExecutor = dbExecutor;
	}

	void updateSettings(Settings settings) {
		notifyPrivateMessages.postValue(settings.getBoolean(
				PREF_NOTIFY_PRIVATE, true));
		notifyGroupMessages.postValue(settings.getBoolean(
				PREF_NOTIFY_GROUP, true));
		notifyVibration.postValue(settings.getBoolean(
				PREF_NOTIFY_VIBRATION, true));
		ringtoneName = settings.get(PREF_NOTIFY_RINGTONE_NAME);
		ringtoneUri = settings.get(PREF_NOTIFY_RINGTONE_URI);
		notifySound.postValue(settings.getBoolean(PREF_NOTIFY_SOUND, true));
	}

	void onRingtoneSet(@Nullable Uri uri) {
		Settings s = new Settings();
		if (uri == null) {
			s.putBoolean(PREF_NOTIFY_SOUND, false);
			s.put(PREF_NOTIFY_RINGTONE_NAME, "");
			s.put(PREF_NOTIFY_RINGTONE_URI, "");
		} else if (RingtoneManager.isDefault(uri)) {
			s.putBoolean(PREF_NOTIFY_SOUND, true);
			s.put(PREF_NOTIFY_RINGTONE_NAME, "");
			s.put(PREF_NOTIFY_RINGTONE_URI, "");
		} else {
			Ringtone r = RingtoneManager.getRingtone(ctx, uri);
			if (r == null || "file".equals(uri.getScheme())) {
				Toast.makeText(ctx, R.string.cannot_load_ringtone, LENGTH_SHORT)
						.show();
			} else {
				String name = r.getTitle(ctx);
				s.putBoolean(PREF_NOTIFY_SOUND, true);
				s.put(PREF_NOTIFY_RINGTONE_NAME, name);
				s.put(PREF_NOTIFY_RINGTONE_URI, uri.toString());
			}
		}
		dbExecutor.execute(() -> {
			try {
				settingsManager.mergeSettings(s, SETTINGS_NAMESPACE);
			} catch (DbException e) {

			}
		});
	}

	LiveData<Boolean> getNotifyPrivateMessages() {
		return notifyPrivateMessages;
	}

	LiveData<Boolean> getNotifyGroupMessages() {
		return notifyGroupMessages;
	}

	LiveData<Boolean> getNotifyVibration() {
		return notifyVibration;
	}

	@NonNull
	LiveData<Boolean> getNotifySound() {
		return notifySound;
	}

	String getRingtoneName() {
		return ringtoneName;
	}

	String getRingtoneUri() {
		return ringtoneUri;
	}
}
