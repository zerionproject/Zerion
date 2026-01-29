package com.professor.zerion.android.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;

import java.util.Random;


public class MatrixRainView extends View {

	private static final int COLOR_TURQUOISE = 0xFF00E1FF;
	private static final int COLOR_CYAN = 0xFF00B8FF;
	private static final int COLOR_DEEP_BLUE = 0xFF0066FF;
	private static final int COLOR_BACKGROUND = 0xFF0B0E15;
	private int statusBarFadeHeight = 80;

	private static final String CHARS = "ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

	private Paint paint;
	private Random random;

	private float[] yPositions;
	private float[] speeds;
	private int[] colors;
	private char[][] columnChars;
	private int columnWidth = 25;
	private int fontSize = 36;
	private int numColumns;
	private int numRows;

	private boolean isRunning = false;

	public MatrixRainView(Context context) {
		super(context);
		init();
	}

	public MatrixRainView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public MatrixRainView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		paint = new Paint();
		paint.setAntiAlias(true);
		paint.setTextSize(fontSize);
		paint.setTypeface(Typeface.MONOSPACE);
		paint.setStyle(Paint.Style.FILL);

		random = new Random();

		isRunning = true;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);

		numColumns = w / columnWidth + 1;
		numRows = h / fontSize + 1;

		yPositions = new float[numColumns];
		speeds = new float[numColumns];
		colors = new int[numColumns];
		columnChars = new char[numColumns][numRows];

		for (int i = 0; i < numColumns; i++) {
			resetColumn(i);
		}
	}

	private void resetColumn(int column) {
		int startVariety = random.nextInt(4);
		if (startVariety == 0) {
			yPositions[column] = random.nextInt(numRows / 3);
		} else {
			yPositions[column] = -random.nextInt(numRows);
		}

		speeds[column] = 0.3f + random.nextFloat() * 0.2f;

		int colorChoice = random.nextInt(3);
		switch (colorChoice) {
			case 0:
				colors[column] = COLOR_TURQUOISE;
				break;
			case 1:
				colors[column] = COLOR_CYAN;
				break;
			case 2:
				colors[column] = COLOR_DEEP_BLUE;
				break;
		}

		for (int j = 0; j < numRows; j++) {
			columnChars[column][j] = CHARS.charAt(random.nextInt(CHARS.length()));
		}
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		if (yPositions == null) return;
		canvas.drawColor(COLOR_BACKGROUND);

		for (int i = 0; i < numColumns; i++) {
			int x = i * columnWidth;
			float yPos = yPositions[i];

			for (int j = 0; j < numRows; j++) {
				int y = (int) ((yPos - j) * fontSize);

				if (y > -fontSize && y < getHeight()) {
					float trailFade = 1.0f - (j / (float) (numRows * 0.8f));
					trailFade = Math.max(0.1f, Math.min(1.0f, trailFade));
					float topFade = 1.0f;
					if (y < statusBarFadeHeight) {
						topFade = (float) y / statusBarFadeHeight;
						topFade = Math.max(0.0f, topFade);
					}

					int color = colors[i];
					int alpha = (int) (trailFade * topFade * 255);
					paint.setColor(Color.argb(alpha,
							Color.red(color),
							Color.green(color),
							Color.blue(color)));

					if (j == 0 && y > statusBarFadeHeight) {
						paint.setShadowLayer(8, 0, 0, color);
					} else {
						paint.clearShadowLayer();
					}

					canvas.drawText(String.valueOf(columnChars[i][j]), x, y, paint);
				}
			}

			if (isRunning) {
				yPositions[i] += speeds[i];

				if (yPositions[i] * fontSize > getHeight() + numRows * fontSize) {
					resetColumn(i);
				}

				if (random.nextFloat() < 0.08f) {
					int randomRow = random.nextInt(numRows);
					columnChars[i][randomRow] = CHARS.charAt(random.nextInt(CHARS.length()));
				}
			}
		}

		if (isRunning) {
			invalidate();
		}
	}

	
	public void setStatusBarHeight(int height) {
		this.statusBarFadeHeight = Math.max(height, 60);
	}

	public void start() {
		isRunning = true;
		invalidate();
	}

	public void stop() {
		isRunning = false;
	}

	public boolean isRunning() {
		return isRunning;
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
