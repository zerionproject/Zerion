package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.appcompat.widget.AppCompatTextView;

@UiThread
public class TabLabel extends AppCompatTextView {

	static final int MAX_LINES = 2;
	private static final float STEP_DP = 0.5f;

	private float preferredPx;
	private float smallestPx;

	public TabLabel(Context context) {
		this(context, null);
	}

	public TabLabel(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		float scale = Math.max(1f,
				getResources().getConfiguration().fontScale);
		preferredPx = getTextSize();
		smallestPx = preferredPx / scale;
	}

	public void setPreferredTextSize(float sp) {
		DisplayMetrics metrics = getResources().getDisplayMetrics();
		preferredPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
				sp, metrics);
		smallestPx = Math.min(preferredPx, TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, sp, metrics));
		requestLayout();
	}

	@Override
	public void setText(CharSequence text, BufferType type) {
		super.setText(text, type);
		requestLayout();
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED
				&& preferredPx > 0) {
			int room = MeasureSpec.getSize(widthSpec)
					- getCompoundPaddingLeft() - getCompoundPaddingRight();
			float size = fittingSize(getPaint(), getText(), room, preferredPx,
					smallestPx, STEP_DP * getResources().getDisplayMetrics()
							.density);
			if (Math.abs(size - getTextSize()) > 0.01f) {
				setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
			}
		}
		super.onMeasure(widthSpec, heightSpec);
	}

	static float fittingSize(TextPaint base, CharSequence text, int room,
			float preferred, float smallest, float step) {
		if (text == null || text.length() == 0 || room <= 0) return preferred;
		TextPaint paint = new TextPaint(base);
		String[] words = text.toString().trim().split("\\s+");
		for (float size = preferred; size >= smallest - 0.01f; size -= step) {
			paint.setTextSize(size);
			if (lineCount(paint, words, room) <= MAX_LINES) return size;
		}
		return smallest;
	}

	static int lineCount(Paint paint, String[] words, int room) {
		float space = paint.measureText(" ");
		int lines = 1;
		float line = -1f;
		for (String word : words) {
			float w = paint.measureText(word);
			if (w > room) return Integer.MAX_VALUE;
			if (line < 0) {
				line = w;
			} else if (line + space + w <= room) {
				line += space + w;
			} else {
				lines++;
				line = w;
			}
		}
		return lines;
	}
}
