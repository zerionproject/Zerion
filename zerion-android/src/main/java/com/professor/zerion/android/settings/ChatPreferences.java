package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class ChatPreferences {

	public static final String PREF_TEXT_SIZE = "pref_chat_text_size";
	public static final String PREF_BUBBLE_COLOR = "pref_bubble_color";
	public static final String PREF_NAV_SIZE = "pref_nav_size";

	public static final int NAV_COMPACT = 0;
	public static final int NAV_DEFAULT = 1;
	public static final int NAV_LARGE = 2;

	public static final int TEXT_SIZE_SMALL = 0;
	public static final int TEXT_SIZE_MEDIUM = 1;
	public static final int TEXT_SIZE_LARGE = 2;
	public static final int TEXT_SIZE_EXTRA_LARGE = 3;

	public static final int BUBBLE_BLUE = 0;
	public static final int BUBBLE_PURPLE = 1;
	public static final int BUBBLE_GREEN = 2;
	public static final int BUBBLE_ORANGE = 3;
	public static final int BUBBLE_PINK = 4;
	public static final int BUBBLE_CYAN = 5;

	private static final float[] TEXT_SIZES_SP = {14f, 16f, 18f, 22f};

	private static final int[] BUBBLE_COLORS = {
			R.color.bubble_blue,
			R.color.bubble_purple,
			R.color.bubble_green,
			R.color.bubble_orange,
			R.color.bubble_pink,
			R.color.bubble_cyan,
	};

	public static float getMessageTextSizeSp(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		int index = prefs.getInt(PREF_TEXT_SIZE, TEXT_SIZE_MEDIUM);
		if (index < 0 || index >= TEXT_SIZES_SP.length) index = TEXT_SIZE_MEDIUM;
		return TEXT_SIZES_SP[index];
	}

	public static int getBubbleColorRes(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		int index = prefs.getInt(PREF_BUBBLE_COLOR, BUBBLE_BLUE);
		if (index < 0 || index >= BUBBLE_COLORS.length) index = BUBBLE_BLUE;
		return BUBBLE_COLORS[index];
	}

	public static int getBubbleColor(Context context) {
		return context.getResources().getColor(getBubbleColorRes(context),
				context.getTheme());
	}

	public static int getNavBarHeightDp(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		int index = prefs.getInt(PREF_NAV_SIZE, NAV_DEFAULT);
		switch (index) {
			case NAV_COMPACT: return 48;
			case NAV_LARGE: return 80;
			default: return 64;
		}
	}

	public static float getNavBarTextSizeSp(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		int index = prefs.getInt(PREF_NAV_SIZE, NAV_DEFAULT);
		switch (index) {
			case NAV_COMPACT: return 12f;
			case NAV_LARGE: return 16f;
			default: return 14f;
		}
	}

	public static void applyBubbleColor(View bubbleView, Context context) {
		if (bubbleView.getBackground() instanceof GradientDrawable) {
			GradientDrawable bg = (GradientDrawable) bubbleView.getBackground();
			bg.setColor(getBubbleColor(context));
		} else {
			bubbleView.setBackgroundColor(getBubbleColor(context));
		}
	}
}
