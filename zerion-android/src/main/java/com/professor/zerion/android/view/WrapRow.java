package com.professor.zerion.android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

@UiThread
public class WrapRow extends LinearLayout {

	private static final String ELLIPSIS = "\u2026";

	private final Map<TextView, Fit> checked = new HashMap<>();
	private boolean stacked;

	public WrapRow(Context context) {
		this(context, null);
	}

	public WrapRow(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public boolean isStacked() {
		return stacked;
	}

	@Override
	protected void onDetachedFromWindow() {
		checked.clear();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		super.onMeasure(widthSpec, heightSpec);
		stacked = getOrientation() == HORIZONTAL
				&& MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
				&& !textFits(this);
		if (stacked) measureStacked(widthSpec, heightSpec);
	}

	private boolean textFits(View v) {
		if (v.getVisibility() == GONE) return true;
		if (v instanceof TextView) return fits((TextView) v);
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				if (!textFits(g.getChildAt(i))) return false;
			}
		}
		return true;
	}

	private boolean fits(TextView t) {
		boolean editable = t.getKeyListener() != null;
		CharSequence text = editable || t.length() == 0 ? t.getHint()
				: shownText(t);
		if (text == null || text.length() == 0) {
			checked.remove(t);
			return true;
		}
		int room = t.getMeasuredWidth() - t.getCompoundPaddingLeft()
				- t.getCompoundPaddingRight();
		boolean oneLine = !editable && t.getMaxLines() == 1;
		boolean shortened = oneLine && t.getEllipsize() != null;
		Paint paint = t.getPaint();
		Fit fit = checked.get(t);
		if (fit == null) {
			fit = new Fit();
			checked.put(t, fit);
		} else if (fit.matches(text, paint, room, oneLine, shortened)) {
			return fit.result;
		}
		fit.remember(text, paint, room, oneLine, shortened,
				measureFits(text, paint, room, oneLine, shortened));
		return fit.result;
	}

	private static boolean measureFits(CharSequence text, Paint paint,
			int room, boolean oneLine, boolean shortened) {
		if (oneLine && TextUtils.indexOf(text, '\n') < 0
				&& paint.measureText(text, 0, text.length()) <= room) {
			return true;
		}
		if (shortened) {
			int start = 0;
			while (start < text.length()
					&& Character.isWhitespace(text.charAt(start))) {
				start++;
			}
			int end = start;
			while (end < text.length()
					&& !Character.isWhitespace(text.charAt(end))) {
				end++;
			}
			return paint.measureText(text, start, end)
					+ paint.measureText(ELLIPSIS) <= room;
		}
		if (oneLine) return false;
		int i = 0;
		int n = text.length();
		while (i < n) {
			while (i < n && Character.isWhitespace(text.charAt(i))) i++;
			int end = i;
			while (end < n && !Character.isWhitespace(text.charAt(end))) end++;
			if (end > i && paint.measureText(text, i, end) > room) return false;
			i = end;
		}
		return true;
	}

	private static CharSequence shownText(TextView t) {
		TransformationMethod method = t.getTransformationMethod();
		CharSequence text = t.getText();
		return method == null ? text : method.getTransformation(text, t);
	}

	private static final class Fit {

		@Nullable
		private CharSequence text;
		@Nullable
		private Typeface typeface;
		private float size;
		private float spacing;
		private int room;
		private boolean oneLine;
		private boolean shortened;
		private boolean result;

		boolean matches(CharSequence text, Paint paint, int room,
				boolean oneLine, boolean shortened) {
			return this.room == room && this.oneLine == oneLine
					&& this.shortened == shortened
					&& size == paint.getTextSize()
					&& spacing == paint.getLetterSpacing()
					&& typeface == paint.getTypeface()
					&& TextUtils.equals(this.text, text);
		}

		void remember(CharSequence text, Paint paint, int room,
				boolean oneLine, boolean shortened, boolean result) {
			this.text = text;
			typeface = paint.getTypeface();
			size = paint.getTextSize();
			spacing = paint.getLetterSpacing();
			this.room = room;
			this.oneLine = oneLine;
			this.shortened = shortened;
			this.result = result;
		}
	}

	private static boolean leftOut(View child) {
		return child.getVisibility() == GONE || child instanceof Space;
	}

	private int stackedGap() {
		Drawable divider = getDividerDrawable();
		return divider != null && (getShowDividers() & SHOW_DIVIDER_MIDDLE) != 0
				? divider.getIntrinsicHeight() : 0;
	}

	private int sharedStartMargin() {
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (leftOut(child)) continue;
			return ((LayoutParams) child.getLayoutParams()).getMarginStart();
		}
		return 0;
	}

	private void measureStacked(int widthSpec, int heightSpec) {
		int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft()
				- getPaddingRight();
		int start = sharedStartMargin();
		int gap = stackedGap();
		int width = 0;
		int height = getPaddingTop() + getPaddingBottom();
		boolean first = true;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (leftOut(child)) continue;
			if (!first) height += gap;
			first = false;
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			int sides = start + lp.getMarginEnd();
			int available = Math.max(0, room - sides);
			boolean fill = lp.width == ViewGroup.LayoutParams.MATCH_PARENT
					|| (lp.width == 0 && lp.weight > 0);
			int childWidth;
			if (fill) {
				childWidth = MeasureSpec.makeMeasureSpec(available,
						MeasureSpec.EXACTLY);
			} else if (lp.width > 0) {
				childWidth = MeasureSpec.makeMeasureSpec(Math.min(lp.width,
						available), MeasureSpec.EXACTLY);
			} else {
				childWidth = MeasureSpec.makeMeasureSpec(available,
						MeasureSpec.AT_MOST);
			}
			int childHeight = lp.height >= 0
					? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
					: MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
			child.measure(childWidth, childHeight);
			width = Math.max(width, child.getMeasuredWidth() + sides);
			height += child.getMeasuredHeight() + lp.topMargin
					+ lp.bottomMargin;
		}
		width += getPaddingLeft() + getPaddingRight();
		if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
			width = MeasureSpec.getSize(widthSpec);
		}
		width = Math.max(width, getSuggestedMinimumWidth());
		height = Math.max(height, getSuggestedMinimumHeight());
		setMeasuredDimension(resolveSize(width, widthSpec),
				resolveSize(height, heightSpec));
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (!stacked) super.onDraw(canvas);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		if (!stacked) {
			super.onLayout(changed, l, t, r, b);
			return;
		}
		int width = r - l;
		boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
		int start = sharedStartMargin();
		int gap = stackedGap();
		int content = 0;
		int shown = 0;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (leftOut(child)) continue;
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			content += child.getMeasuredHeight() + lp.topMargin
					+ lp.bottomMargin;
			shown++;
		}
		content += Math.max(0, shown - 1) * gap;
		int spare = (b - t) - getPaddingTop() - getPaddingBottom() - content;
		int vertical = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
		int y = getPaddingTop();
		if (spare > 0 && vertical == Gravity.CENTER_VERTICAL) {
			y += spare / 2;
		} else if (spare > 0 && vertical == Gravity.BOTTOM) {
			y += spare;
		}
		int startEdge = rtl ? width - getPaddingRight() : getPaddingLeft();
		boolean first = true;
		for (int i = 0; i < getChildCount(); i++) {
			View child = getChildAt(i);
			if (child instanceof Space && child.getVisibility() != GONE) {
				child.layout(0, 0, 0, 0);
			}
			if (leftOut(child)) continue;
			if (!first) y += gap;
			first = false;
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			int w = child.getMeasuredWidth();
			int h = child.getMeasuredHeight();
			int gravity = lp.gravity < 0 ? Gravity.START : lp.gravity;
			int horizontal = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
			int x;
			if (horizontal == Gravity.CENTER_HORIZONTAL) {
				x = getPaddingLeft() + (width - getPaddingLeft()
						- getPaddingRight() - w) / 2;
			} else if (horizontal == Gravity.END
					|| (horizontal == Gravity.RIGHT && !rtl)
					|| (horizontal == Gravity.LEFT && rtl)) {
				x = rtl ? getPaddingLeft() + lp.getMarginEnd()
						: width - getPaddingRight() - lp.getMarginEnd() - w;
			} else {
				x = rtl ? startEdge - start - w : startEdge + start;
			}
			y += lp.topMargin;
			child.layout(x, y, x + w, y + h);
			y += h + lp.bottomMargin;
		}
	}
}
