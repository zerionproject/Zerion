package com.professor.zerion.android;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import javax.annotation.Nullable;

import static android.os.Build.VERSION.SDK_INT;

@NotNullByDefault
public class Localizer {

	@Nullable
	private static Localizer INSTANCE;
	private final Locale systemLocale;
	private final Locale locale;

	private Localizer(String languageTag) {
		this(Locale.getDefault(), getLocaleFromTag(languageTag));
	}

	private Localizer(Locale systemLocale, @Nullable Locale userLocale) {
		this.systemLocale = systemLocale;
		if (userLocale == null) locale = systemLocale;
		else locale = userLocale;
	}

	public static synchronized void initialize(String languageTag) {
		if (INSTANCE == null)
			INSTANCE = new Localizer(languageTag);
	}

	public static synchronized void reinitialize() {
		if (INSTANCE != null)
			INSTANCE = new Localizer(INSTANCE.systemLocale, null);
	}

	public static synchronized void forceReinitialize(String languageTag) {
		Locale system = INSTANCE == null
				? Locale.getDefault() : INSTANCE.systemLocale;
		INSTANCE = new Localizer(system, getLocaleFromTag(languageTag));
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

	public Context applyLocaleToContext(Context context) {
		Resources res = context.getResources();
		Configuration conf = new Configuration(res.getConfiguration());
		Locale currentLocale;
		if (SDK_INT >= 24) {
			currentLocale = conf.getLocales().get(0);
		} else {
			currentLocale = conf.locale;
		}
		Locale.setDefault(locale);
		if (locale.equals(currentLocale)) {
			return context;
		}
		conf.setLocale(locale);
		return context.createConfigurationContext(conf);
	}

	public void updateResources(Context context) {
		Locale.setDefault(locale);
		Resources res = context.getResources();
		Configuration conf = new Configuration(res.getConfiguration());
		conf.setLocale(locale);
		res.updateConfiguration(conf, res.getDisplayMetrics());
	}

	public void setLocaleWithPersistence(Context context) {
		if (SDK_INT >= 33) {
			LocaleManager localeManager = context.getSystemService(LocaleManager.class);
			if (localeManager != null) {
				localeManager.setApplicationLocales(new LocaleList(locale));
			}
		}
	}
}
