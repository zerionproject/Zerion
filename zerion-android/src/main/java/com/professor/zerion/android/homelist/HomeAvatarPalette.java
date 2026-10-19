package com.professor.zerion.android.homelist;

import android.content.Context;
import android.content.res.TypedArray;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

@NotNullByDefault
public final class HomeAvatarPalette {

	@ColorInt
	private final int[] fills;
	@ColorInt
	private final int label;

	private HomeAvatarPalette(int[] fills, int label) {
		this.fills = fills;
		this.label = label;
	}

	public static HomeAvatarPalette load(Context ctx) {
		TypedArray a = ctx.getResources().obtainTypedArray(
				R.array.zerion_avatar_palette);
		int[] fills = new int[a.length()];
		for (int i = 0; i < fills.length; i++) fills[i] = a.getColor(i, 0);
		a.recycle();
		return new HomeAvatarPalette(fills,
				ContextCompat.getColor(ctx, R.color.zerion_avatar_label));
	}

	static int indexFor(String key, int size) {
		return Math.floorMod(key.hashCode(), size);
	}

	@ColorInt
	public int fillFor(String key) {
		return fills[indexFor(key, fills.length)];
	}

	@ColorInt
	public int getLabel() {
		return label;
	}

	int size() {
		return fills.length;
	}
}
