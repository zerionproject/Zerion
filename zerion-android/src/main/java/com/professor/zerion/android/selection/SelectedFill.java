package com.professor.zerion.android.selection;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.util.UiUtils;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.UiThread;
import androidx.core.graphics.ColorUtils;

@UiThread
@NotNullByDefault
public final class SelectedFill {

	private static final int[] SELECTED = {android.R.attr.state_activated};
	private static final int[][] STATES = {SELECTED, {}};
	private static final long OWN = 1L << 32;
	private static final long OVER = 2L << 32;

	private SelectedFill() {
	}

	public static void received(@Nullable View bubble) {
		over(bubble, R.attr.zerionColorMessageSelectedReceived);
	}

	public static void card(@Nullable View card) {
		over(card, R.attr.zerionColorChannelCardSelected);
	}

	public static void own(@Nullable View bubble, @ColorInt int fill) {
		if (bubble == null || painted(bubble, OWN, fill)) return;
		int layer = UiUtils.resolveColorAttribute(bubble.getContext(),
				R.attr.zerionColorMessageSelectedOwn);
		paint(bubble, ColorUtils.compositeColors(layer, fill), fill,
				PorterDuff.Mode.SRC_IN, OWN, fill);
	}

	public static void bare(View bubble) {
		bubble.setTag(R.id.selected_fill, null);
		bubble.setBackgroundTintList(null);
		bubble.setBackgroundResource(R.drawable.bg_message_selected_bare);
	}

	private static void over(@Nullable View v, @AttrRes int layer) {
		if (v == null || painted(v, OVER, layer)) return;
		paint(v, UiUtils.resolveColorAttribute(v.getContext(), layer),
				Color.TRANSPARENT, PorterDuff.Mode.SRC_ATOP, OVER, layer);
	}

	private static boolean painted(View v, long kind, int value) {
		Object tag = v.getTag(R.id.selected_fill);
		return tag instanceof Long && (Long) tag == key(kind, value)
				&& v.getBackgroundTintList() != null;
	}

	private static long key(long kind, int value) {
		return kind | (value & 0xFFFFFFFFL);
	}

	private static void paint(View v, @ColorInt int selected,
			@ColorInt int normal, PorterDuff.Mode mode, long kind,
			int value) {
		v.setBackgroundTintList(new ColorStateList(STATES,
				new int[] {selected, normal}));
		v.setBackgroundTintMode(mode);
		v.setTag(R.id.selected_fill, key(kind, value));
	}
}
