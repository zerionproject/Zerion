package com.professor.zerion.android;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import javax.annotation.Nullable;

import static android.os.Build.VERSION.SDK_INT;
import static com.professor.zerion.android.settings.DisplayFragment.PREF_LANGUAGE;

@NotNullByDefault
public class Localizer {

	@Nullable
	private static Localizer INSTANCE;
	private final Locale systemLocale;
	private final Locale locale;

	private Localizer(SharedPreferences sharedPreferences) {
		this(Locale.getDefault(), getLocaleFromTag(
				sharedPreferences.getString(PREF_LANGUAGE, "default")));
	}

	private Localizer(Locale systemLocale, @Nullable Locale userLocale) {
		this.systemLocale = systemLocale;
		if (userLocale == null) locale = systemLocale;
		else locale = userLocale;
	}

	public static synchronized void initialize(SharedPreferences prefs) {
		if (INSTANCE == null)
			INSTANCE = new Localizer(prefs);
	}

	public static synchronized void reinitialize() {
		if (INSTANCE != null)
			INSTANCE = new Localizer(INSTANCE.systemLocale, null);
	}

	public static synchronized Localizer getInstance() {
		if (INSTANCE == null)
			throw new IllegalStateException("Localizer not initialized");
		return INSTANCE;
	}

	@Nullable
	public static Locale getLocaleFromTag(String tag) {
		if (tag.equals("default")) return null;
		return Locale.forLanguageTag(tag);
	}

	/**
	 * Apply locale to context in-memory only - NO disk I/O.
	 * Use this in attachBaseContext() to avoid blocking the main thread.
	 * This method only creates a configuration context with the locale,
	 * without calling LocaleManager.setApplicationLocales().
	 */
	public Context applyLocaleToContext(Context context) {
		Resources res = context.getResources();
		Configuration conf = new Configuration(res.getConfiguration());
		Locale currentLocale;
		if (SDK_INT >= 24) {
			currentLocale = conf.getLocales().get(0);
		} else {
			currentLocale = conf.locale;
		}
		if (locale.equals(currentLocale)) {
			return context;
		}
		Locale.setDefault(locale);
		conf.setLocale(locale);
		return context.createConfigurationContext(conf);
	}

	/**
	 * Full locale setting including LocaleManager for Android 13+.
	 * Use this when user explicitly changes language in settings.
	 * WARNING: This triggers disk I/O on Android 13+ - do NOT call from attachBaseContext().
	 */
	public void setLocaleWithPersistence(Context context) {
		if (SDK_INT >= 33) {
			LocaleManager localeManager = context.getSystemService(LocaleManager.class);
			if (localeManager != null) {
				localeManager.setApplicationLocales(new LocaleList(locale));
			}
		}
	}

	/**
	 * @deprecated Use {@link #applyLocaleToContext(Context)} in attachBaseContext()
	 * and {@link #setLocaleWithPersistence(Context)} when changing settings.
	 */
	@Deprecated
	public Context setLocale(Context context) {
		// For backwards compatibility, just apply locale to context
		// without the LocaleManager call to avoid disk I/O
		return applyLocaleToContext(context);
	}
}
