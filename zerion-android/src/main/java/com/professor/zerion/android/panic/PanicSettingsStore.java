package com.professor.zerion.android.panic;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

import javax.annotation.Nullable;

import info.guardianproject.panic.Panic;

import static info.guardianproject.panic.Panic.PACKAGE_NAME_NONE;

@NotNullByDefault
public final class PanicSettingsStore {

	static final String LIBRARY_TRIGGER_KEY =
			"panicResponderTriggerPackageName";
	static final String KEY_TRIGGER_PACKAGE = "pref_key_panic_trigger";
	private static final String DEFAULT = "DEFAULT";

	private static final String ACTION_CONNECT =
			"info.guardianproject.panic.action.CONNECT";
	private static final String ACTION_DISCONNECT =
			"info.guardianproject.panic.action.DISCONNECT";

	private final SharedPreferences encrypted;

	public PanicSettingsStore(SharedPreferences encrypted) {
		this.encrypted = encrypted;
	}

	public static void migrate(Context context, SharedPreferences encrypted) {
		Context app = context.getApplicationContext();
		SharedPreferences plain = androidx.preference.PreferenceManager
				.getDefaultSharedPreferences(app);
		String trigger = plain.getString(LIBRARY_TRIGGER_KEY, null);
		if (trigger != null && !encrypted.contains(KEY_TRIGGER_PACKAGE)) {
			encrypted.edit().putString(KEY_TRIGGER_PACKAGE, trigger).commit();
		}
		boolean had = plain.contains(LIBRARY_TRIGGER_KEY)
				|| plain.contains(PanicPreferencesFragment.KEY_LOCK)
				|| plain.contains(PanicPreferencesFragment.KEY_PURGE)
				|| plain.contains(PanicPreferencesFragment.KEY_PANIC_APP);
		if (had) {
			plain.edit()
					.remove(LIBRARY_TRIGGER_KEY)
					.remove(PanicPreferencesFragment.KEY_LOCK)
					.remove(PanicPreferencesFragment.KEY_PURGE)
					.remove(PanicPreferencesFragment.KEY_PANIC_APP)
					.commit();
		}
		File marker = new File(new File(app.getApplicationInfo().dataDir,
				"shared_prefs"), "_has_set_default_values.xml");
		if (marker.exists()) {
			app.getSharedPreferences("_has_set_default_values",
					Context.MODE_PRIVATE).edit().clear().commit();
			if (!marker.delete()) marker.deleteOnExit();
		}
	}

	@Nullable
	public String getTriggerPackageName() {
		String p = encrypted.getString(KEY_TRIGGER_PACKAGE, null);
		return TextUtils.isEmpty(p) ? null : p;
	}

	public void setTriggerPackageName(Activity activity,
			@Nullable String packageName) {
		PackageManager pm = activity.getPackageManager();
		String previous = getTriggerPackageName();
		if (previous != null && !previous.equals(packageName)) {
			Intent disconnect = new Intent(ACTION_DISCONNECT);
			disconnect.setPackage(previous);
			if (!pm.queryIntentActivities(disconnect, 0).isEmpty()) {
				activity.startActivityForResult(disconnect, 0);
			}
		}
		if (TextUtils.isEmpty(packageName) || DEFAULT.equals(packageName)
				|| PACKAGE_NAME_NONE.equals(packageName)) {
			encrypted.edit().remove(KEY_TRIGGER_PACKAGE).commit();
			return;
		}
		encrypted.edit().putString(KEY_TRIGGER_PACKAGE, packageName).commit();
		Intent connect = new Intent(ACTION_CONNECT);
		connect.setPackage(packageName);
		if (!pm.queryIntentActivities(connect, 0).isEmpty()) {
			activity.startActivityForResult(connect, 0);
		}
	}

	public void setTriggerPackageNameFromCaller(Activity activity) {
		String caller = callingPackage(activity);
		if (caller != null) setTriggerPackageName(activity, caller);
	}

	public boolean receivedTriggerFromConnectedApp(Activity activity) {
		if (!Panic.isTriggerIntent(activity.getIntent())) return false;
		String caller = callingPackage(activity);
		return caller != null && caller.equals(getTriggerPackageName());
	}

	public boolean checkForDisconnectIntent(Activity activity) {
		Intent i = activity.getIntent();
		if (i == null || !ACTION_DISCONNECT.equals(i.getAction())) {
			return false;
		}
		String caller = callingPackage(activity);
		if (caller != null && caller.equals(getTriggerPackageName())) {
			encrypted.edit().remove(KEY_TRIGGER_PACKAGE).commit();
		}
		return true;
	}

	@Nullable
	static String callingPackage(Activity activity) {
		ComponentName c = activity.getCallingActivity();
		if (c == null) return null;
		String p = c.getPackageName();
		return TextUtils.isEmpty(p) ? null : p;
	}
}
