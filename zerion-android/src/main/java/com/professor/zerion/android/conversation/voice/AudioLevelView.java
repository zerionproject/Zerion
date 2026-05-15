package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class AudioLevelView extends View {

	private static final int BAR_COUNT = 5;
	private static final float MIN_BAR_HEIGHT = 0.2f;
	private static final float MAX_BAR_HEIGHT = 1.0f;

	private final Paint paint;
	private final float[] barHeights = new float[BAR_COUNT];
	private int currentAmplitude = 0;

	public AudioLevelView(Context context) {
		this(context, null);
	}

	public AudioLevelView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AudioLevelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setColor(ContextCompat.getColor(context, R.color.zerion_lime_600));
		paint.setStyle(Paint.Style.FILL);
		paint.setStrokeCap(Paint.Cap.ROUND);
		for (int i = 0; i < BAR_COUNT; i++) {
			barHeights[i] = MIN_BAR_HEIGHT;
		}
	}

	public void setAmplitude(int amplitude) {
		currentAmplitude = Math.max(0, Math.min(100, amplitude));
		updateBarHeights();
		invalidate();
	}

	private void updateBarHeights() {
		float targetHeight = MIN_BAR_HEIGHT + (currentAmplitude / 100f) * (MAX_BAR_HEIGHT - MIN_BAR_HEIGHT);

		for (int i = 0; i < BAR_COUNT; i++) {
			float variation = (float) (Math.random() * 0.3 - 0.15);
			barHeights[i] = Math.max(MIN_BAR_HEIGHT,
					Math.min(MAX_BAR_HEIGHT, targetHeight + variation));
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		int width = getWidth();
		int height = getHeight();

		if (width == 0 || height == 0) return;

		float barWidth = width / (float) (BAR_COUNT * 2 - 1);
		float maxBarHeight = height * 0.8f;

		for (int i = 0; i < BAR_COUNT; i++) {
			float x = i * barWidth * 2 + barWidth / 2;
			float barHeight = maxBarHeight * barHeights[i];
			float y = (height - barHeight) / 2;

			canvas.drawRoundRect(
					x - barWidth / 2,
					y,
					x + barWidth / 2,
					y + barHeight,
					barWidth / 2,
					barWidth / 2,
					paint
			);
		}
		if (currentAmplitude > 0) {
			postInvalidateDelayed(100);
		}
	}
}
