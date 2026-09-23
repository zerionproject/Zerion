package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.ZerionEncryptedPrefs;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import static android.content.Context.MODE_PRIVATE;
import static com.professor.zerion.android.settings.ChatPreferences.PREF_GUI_TEXT_SIZE;
import static com.professor.zerion.android.settings.DisplayFragment.PREF_LANGUAGE;
import static com.professor.zerion.android.settings.DisplayFragment.PREF_THEME;

/**
 * The three display settings the process needs before the account is
 * unlocked: the language tag, the theme name and the text size index. They
 * live in a boot-readable encrypted store and, when that store cannot be
 * created, in a plain private file. Nothing else is ever written here: the
 * store is not exposed, so a caller cannot add a key to it.
 */
@NotNullByDefault
public final class EarlyPrefs {

	private static final String FILE = "early_ui_prefs";
	private static final String DEFAULT_LANGUAGE = "default";

	@Nullable
	private static volatile SharedPreferences cached;

	private EarlyPrefs() {
	}

	public static String language(Context ctx) {
		String tag = prefs(ctx).getString(PREF_LANGUAGE, DEFAULT_LANGUAGE);
		return tag == null ? DEFAULT_LANGUAGE : tag;
	}

	public static void setLanguage(Context ctx, String tag) {
		prefs(ctx).edit().putString(PREF_LANGUAGE, tag).commit();
	}

	@Nullable
	public static String theme(Context ctx) {
		return prefs(ctx).getString(PREF_THEME, null);
	}

	public static void setTheme(Context ctx, String theme) {
		prefs(ctx).edit().putString(PREF_THEME, theme).commit();
	}

	public static int guiTextSize(Context ctx, int defaultIndex) {
		return prefs(ctx).getInt(PREF_GUI_TEXT_SIZE, defaultIndex);
	}

	public static void setGuiTextSize(Context ctx, int index) {
		prefs(ctx).edit().putInt(PREF_GUI_TEXT_SIZE, index).commit();
	}

	private static SharedPreferences prefs(Context ctx) {
		SharedPreferences c = cached;
		if (c != null) return c;
		try {
			c = ZerionEncryptedPrefs.createBootReadable(ctx, FILE);
		} catch (Throwable fallback) {
			c = ctx.getSharedPreferences(FILE, MODE_PRIVATE);
		}
		cached = c;
		return c;
	}
}
