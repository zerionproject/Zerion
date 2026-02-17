package com.professor.zerion.android;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.StrictMode.VmPolicy;

import com.google.android.material.color.DynamicColors;
import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import org.briarproject.bramble.BrambleAndroidEagerSingletons;
import org.briarproject.bramble.BrambleAppComponent;
import org.briarproject.bramble.BrambleCoreEagerSingletons;
import org.briarproject.briar.BriarCoreEagerSingletons;
import com.professor.zerion.R;
import com.professor.zerion.android.util.UiUtils;

import java.lang.Thread.UncaughtExceptionHandler;
import androidx.annotation.NonNull;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;
import static com.professor.zerion.android.settings.DisplayFragment.PREF_THEME;

public class ZerionApplicationImpl extends Application
		implements ZerionApplication {

	private AndroidComponent applicationComponent;
	private volatile SharedPreferences prefs;

	@Override
	protected void attachBaseContext(Context base) {
		if (prefs == null)
			prefs = EarlyPrefs.get(base);
		Localizer.initialize(prefs);
		super.attachBaseContext(
				Localizer.getInstance().applyLocaleToContext(base));
		setTheme(base, prefs);
	}

	@Override
	public void onCreate() {
		super.onCreate();

		suppressBouncyCastleWarning();

		DynamicColors.applyToActivitiesIfAvailable(this);

		if (IS_DEBUG_BUILD) enableStrictMode();

		applicationComponent = createApplicationComponent();
		UncaughtExceptionHandler exceptionHandler =
				applicationComponent.exceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(exceptionHandler);

		EmojiManager.install(new GoogleEmojiProvider());
	}

	private void suppressBouncyCastleWarning() {
		java.util.logging.Logger.getLogger("org.bouncycastle.util.Strings")
				.setLevel(java.util.logging.Level.OFF);
	}

	protected AndroidComponent createApplicationComponent() {
		AndroidComponent androidComponent = DaggerAndroidComponent.builder()
				.appModule(new AppModule(this))
				.build();
		new Thread(() -> {
			BrambleCoreEagerSingletons.Helper
					.injectEagerSingletons(androidComponent);
			BrambleAndroidEagerSingletons.Helper
					.injectEagerSingletons(androidComponent);
			BriarCoreEagerSingletons.Helper.injectEagerSingletons(androidComponent);
			AndroidEagerSingletons.Helper.injectEagerSingletons(androidComponent);
		}, "EagerSingletonsInit").start();

		return androidComponent;
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		Localizer.getInstance().applyLocaleToContext(this);
	}

	private void setTheme(Context ctx, SharedPreferences prefs) {
		String theme = prefs.getString(PREF_THEME, null);
		if (theme == null) {
			theme = getString(R.string.pref_theme_dark_value);
			prefs.edit().putString(PREF_THEME, theme).apply();
		}
		UiUtils.setTheme(ctx, theme);
	}

	private void enableStrictMode() {
		ThreadPolicy.Builder threadPolicy = new ThreadPolicy.Builder();
		threadPolicy.detectAll();
		threadPolicy.penaltyLog();
		StrictMode.setThreadPolicy(threadPolicy.build());
		VmPolicy.Builder vmPolicy = new VmPolicy.Builder();
		vmPolicy.detectAll();
		vmPolicy.penaltyLog();
		StrictMode.setVmPolicy(vmPolicy.build());
	}

	@Override
	public BrambleAppComponent getBrambleAppComponent() {
		return applicationComponent;
	}

	@Override
	public AndroidComponent getApplicationComponent() {
		return applicationComponent;
	}

	@Deprecated
	@Override
	public SharedPreferences getDefaultSharedPreferences() {
		if (applicationComponent != null) {
			return applicationComponent.uiPreferences();
		}
		return prefs;
	}

	@Override
	public boolean isRunningInBackground() {
		RunningAppProcessInfo info = new RunningAppProcessInfo();
		ActivityManager.getMyMemoryState(info);
		return (info.importance != IMPORTANCE_FOREGROUND);
	}

	@Override
	public boolean isInstrumentationTest() {
		return false;
	}
}
