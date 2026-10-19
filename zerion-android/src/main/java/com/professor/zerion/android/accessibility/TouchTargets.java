package com.professor.zerion.android.accessibility;

import android.graphics.Rect;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewParent;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

@UiThread
@NotNullByDefault
public final class TouchTargets {

	static final float MIN_DP = 48f;

	private TouchTargets() {
	}

	public static void atLeast48dp(View child, @Nullable View above) {
		if (child.getTag(R.id.touch_target) != null) return;
		child.setTag(R.id.touch_target, Boolean.TRUE);
		child.addOnLayoutChangeListener(
				(v, l, t, r, b, ol, ot, or, ob) -> grow(v, above));
	}

	public static void drop(View child) {
		ViewParent p = child.getParent();
		if (!(p instanceof View)) return;
		View parent = (View) p;
		if (parent.getTag(R.id.touch_target) == child) {
			parent.setTag(R.id.touch_target, null);
			parent.setTouchDelegate(null);
		}
	}

	@VisibleForTesting
	static void grow(View child, @Nullable View above) {
		ViewParent p = child.getParent();
		if (!(p instanceof View)) return;
		View parent = (View) p;
		if (child.getVisibility() != View.VISIBLE || !child.isClickable()) {
			drop(child);
			return;
		}
		int min = Math.round(MIN_DP
				* child.getResources().getDisplayMetrics().density);
		Rect r = new Rect();
		child.getHitRect(r);
		int floor = above == null || above.getParent() != parent ? 0
				: Math.min(above.getBottom(), r.top);
		int[] x = widen(r.left, r.right, min, 0, parent.getWidth());
		int[] y = widen(r.top, r.bottom, min, floor, parent.getHeight());
		r.set(x[0], y[0], x[1], y[1]);
		parent.setTouchDelegate(new TouchDelegate(r, child));
		parent.setTag(R.id.touch_target, child);
	}

	static int[] widen(int start, int end, int min, int low, int high) {
		if (end - start >= min) return new int[] {start, end};
		int from = start - (min - (end - start)) / 2;
		int to = from + min;
		if (from < low) {
			to += low - from;
			from = low;
		}
		if (to > high) {
			from = Math.max(low, from - (to - high));
			to = high;
		}
		return new int[] {from, to};
	}
}
