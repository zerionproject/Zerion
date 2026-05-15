package com.professor.zerion.android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class ParticleFieldView extends View {

	private static final int COLOR_TURQUOISE = 0xFF00E1FF;
	private static final int COLOR_CYAN = 0xFF00B8FF;
	private static final int COLOR_DEEP_BLUE = 0xFF0066FF;
	private static final int COLOR_BACKGROUND = 0xFF0B0E15;

	private static final int PARTICLE_COUNT = 65;
	private static final float CONNECTION_DISTANCE = 150f;
	private static final float MIN_RADIUS = 1.5f;
	private static final float MAX_RADIUS = 3.5f;
	private static final float MIN_SPEED = 0.15f;
	private static final float MAX_SPEED = 0.6f;
	private static final float MIN_ALPHA = 0.25f;
	private static final float MAX_ALPHA = 0.9f;
	private static final float LINE_MAX_ALPHA = 0.3f;
	private static final float LINE_WIDTH = 0.8f;

	private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Random random = new Random();

	private float[] px, py;
	private float[] vx, vy;
	private float[] radii;
	private float[] alphas;
	private int[] colors;
	private float[] wobblePhase;

	private int viewWidth, viewHeight;
	private volatile boolean isRunning = false;
	private long lastFrameTime = 0;

	public ParticleFieldView(Context context) {
		super(context);
		init();
	}

	public ParticleFieldView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public ParticleFieldView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		particlePaint.setStyle(Paint.Style.FILL);
		linePaint.setStyle(Paint.Style.STROKE);
		linePaint.setStrokeWidth(LINE_WIDTH);
		isRunning = true;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		viewWidth = w;
		viewHeight = h;
		initParticles();
	}

	private void initParticles() {
		int count = PARTICLE_COUNT;
		px = new float[count];
		py = new float[count];
		vx = new float[count];
		vy = new float[count];
		radii = new float[count];
		alphas = new float[count];
		colors = new int[count];
		wobblePhase = new float[count];

		int[] palette = {COLOR_TURQUOISE, COLOR_CYAN, COLOR_DEEP_BLUE};

		for (int i = 0; i < count; i++) {
			px[i] = random.nextFloat() * viewWidth;
			py[i] = random.nextFloat() * viewHeight;

			float angle = random.nextFloat() * (float) (2 * Math.PI);
			float speed = MIN_SPEED + random.nextFloat() * (MAX_SPEED - MIN_SPEED);
			vx[i] = (float) Math.cos(angle) * speed;
			vy[i] = (float) Math.sin(angle) * speed;

			radii[i] = MIN_RADIUS + random.nextFloat() * (MAX_RADIUS - MIN_RADIUS);
			alphas[i] = MIN_ALPHA + random.nextFloat() * (MAX_ALPHA - MIN_ALPHA);
			colors[i] = palette[random.nextInt(palette.length)];
			wobblePhase[i] = random.nextFloat() * (float) (2 * Math.PI);
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (px == null) return;

		long now = System.nanoTime();
		float dt = lastFrameTime == 0 ? 16f : (now - lastFrameTime) / 1_000_000f;
		lastFrameTime = now;

		dt = Math.min(dt, 50f);

		canvas.drawColor(COLOR_BACKGROUND);

		int count = px.length;

		for (int i = 0; i < count; i++) {

			float wobble = (float) Math.sin(wobblePhase[i]) * 0.05f;
			px[i] += (vx[i] + wobble) * dt;
			py[i] += (vy[i] + wobble * 0.7f) * dt;
			wobblePhase[i] += 0.002f * dt;

			if (px[i] < -20) px[i] = viewWidth + 20;
			if (px[i] > viewWidth + 20) px[i] = -20;
			if (py[i] < -20) py[i] = viewHeight + 20;
			if (py[i] > viewHeight + 20) py[i] = -20;
		}

		float connDistSq = CONNECTION_DISTANCE * CONNECTION_DISTANCE;
		for (int i = 0; i < count; i++) {
			for (int j = i + 1; j < count; j++) {
				float dx = px[i] - px[j];
				float dy = py[i] - py[j];
				float distSq = dx * dx + dy * dy;
				if (distSq < connDistSq) {
					float dist = (float) Math.sqrt(distSq);
					float lineAlpha = (1f - dist / CONNECTION_DISTANCE) * LINE_MAX_ALPHA;

					int color = colors[i];
					linePaint.setColor(color);
					linePaint.setAlpha((int) (lineAlpha * 255));
					canvas.drawLine(px[i], py[i], px[j], py[j], linePaint);
				}
			}
		}

		for (int i = 0; i < count; i++) {
			particlePaint.setColor(colors[i]);
			particlePaint.setAlpha((int) (alphas[i] * 255));
			canvas.drawCircle(px[i], py[i], radii[i], particlePaint);

			if (alphas[i] > 0.6f) {
				particlePaint.setAlpha((int) (alphas[i] * 40));
				canvas.drawCircle(px[i], py[i], radii[i] * 3f, particlePaint);
			}
		}

		if (isRunning) {
			postInvalidateOnAnimation();
		}
	}

	public void start() {
		isRunning = true;
		lastFrameTime = 0;
		postInvalidateOnAnimation();
	}

	public void stop() {
		isRunning = false;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		start();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		stop();
	}
}
