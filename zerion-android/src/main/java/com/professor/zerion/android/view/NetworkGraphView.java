package com.professor.zerion.android.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class NetworkGraphView extends View {
	private static final int MAX_DATA_POINTS = 60;
	private static final float GRID_LINE_COUNT = 4;
	private static final float CORNER_RADIUS = 16f;
	@ColorInt private static final int COLOR_DOWNLOAD = 0xFF26B7F0;
	@ColorInt private static final int COLOR_UPLOAD = 0xFF4CAF50;
	@ColorInt private static final int COLOR_GRID = 0x33FFFFFF;
	@ColorInt private static final int COLOR_BACKGROUND = 0xFF1A1A1A;
	@ColorInt private static final int COLOR_LABEL = 0xFFAAAAAA;
	@ColorInt private static final int COLOR_VALUE = 0xFFFFFFFF;
	private final Paint downloadLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint uploadLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint downloadFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint uploadFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path downloadPath = new Path();
	private final Path uploadPath = new Path();
	private final Path downloadFillPath = new Path();
	private final Path uploadFillPath = new Path();
	private final RectF backgroundRect = new RectF();
	private final RectF graphRect = new RectF();
	private final List<Long> downloadData = new ArrayList<>();
	private final List<Long> uploadData = new ArrayList<>();
	private long maxValue = 1024;
	private long currentDownload = 0;
	private long currentUpload = 0;
	private long totalDownload = 0;
	private long totalUpload = 0;
	private ValueAnimator pulseAnimator;
	private float pulseAlpha = 1f;
	private float graphPaddingLeft;
	private float graphPaddingRight;
	private float graphPaddingTop;
	private float graphPaddingBottom;
	private float legendHeight;

	public NetworkGraphView(Context context) {
		super(context);
		init();
	}

	public NetworkGraphView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public NetworkGraphView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		float density = getResources().getDisplayMetrics().density;
		graphPaddingLeft = 56 * density;
		graphPaddingRight = 16 * density;
		graphPaddingTop = 16 * density;
		graphPaddingBottom = 24 * density;
		legendHeight = 48 * density;
		downloadLinePaint.setStyle(Paint.Style.STROKE);
		downloadLinePaint.setStrokeWidth(2.5f * density);
		downloadLinePaint.setColor(COLOR_DOWNLOAD);
		downloadLinePaint.setStrokeCap(Paint.Cap.ROUND);
		downloadLinePaint.setStrokeJoin(Paint.Join.ROUND);
		uploadLinePaint.setStyle(Paint.Style.STROKE);
		uploadLinePaint.setStrokeWidth(2.5f * density);
		uploadLinePaint.setColor(COLOR_UPLOAD);
		uploadLinePaint.setStrokeCap(Paint.Cap.ROUND);
		uploadLinePaint.setStrokeJoin(Paint.Join.ROUND);
		downloadFillPaint.setStyle(Paint.Style.FILL);
		uploadFillPaint.setStyle(Paint.Style.FILL);
		gridPaint.setStyle(Paint.Style.STROKE);
		gridPaint.setStrokeWidth(1f);
		gridPaint.setColor(COLOR_GRID);
		backgroundPaint.setStyle(Paint.Style.FILL);
		backgroundPaint.setColor(COLOR_BACKGROUND);
		labelPaint.setTextSize(11 * density);
		labelPaint.setColor(COLOR_LABEL);
		labelPaint.setTextAlign(Paint.Align.RIGHT);
		valuePaint.setTextSize(13 * density);
		valuePaint.setColor(COLOR_VALUE);
		valuePaint.setTextAlign(Paint.Align.LEFT);
		legendPaint.setTextSize(12 * density);
		legendPaint.setTextAlign(Paint.Align.LEFT);
		for (int i = 0; i < MAX_DATA_POINTS; i++) {
			downloadData.add(0L);
			uploadData.add(0L);
		}
		startPulseAnimation();
	}

	private void startPulseAnimation() {
		pulseAnimator = ValueAnimator.ofFloat(0.6f, 1f);
		pulseAnimator.setDuration(1500);
		pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
		pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
		pulseAnimator.setInterpolator(new LinearInterpolator());
		pulseAnimator.addUpdateListener(animation -> {
			pulseAlpha = (float) animation.getAnimatedValue();
			invalidate();
		});
		pulseAnimator.start();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (pulseAnimator != null) {
			pulseAnimator.cancel();
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (pulseAnimator != null && !pulseAnimator.isRunning()) {
			pulseAnimator.start();
		}
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		backgroundRect.set(0, 0, w, h);
		float graphBottom = h - graphPaddingBottom - legendHeight;
		graphRect.set(graphPaddingLeft, graphPaddingTop, w - graphPaddingRight, graphBottom);
		updateGradients();
	}

	private void updateGradients() {
		if (graphRect.height() <= 0) return;
		LinearGradient downloadGradient = new LinearGradient(
				0, graphRect.top,
				0, graphRect.bottom,
				new int[]{
						(COLOR_DOWNLOAD & 0x00FFFFFF) | 0x40000000,
						(COLOR_DOWNLOAD & 0x00FFFFFF) | 0x08000000
				},
				new float[]{0f, 1f},
				Shader.TileMode.CLAMP
		);
		downloadFillPaint.setShader(downloadGradient);
		LinearGradient uploadGradient = new LinearGradient(
				0, graphRect.top,
				0, graphRect.bottom,
				new int[]{
						(COLOR_UPLOAD & 0x00FFFFFF) | 0x30000000,
						(COLOR_UPLOAD & 0x00FFFFFF) | 0x08000000
				},
				new float[]{0f, 1f},
				Shader.TileMode.CLAMP
		);
		uploadFillPaint.setShader(uploadGradient);
	}

	
	public void addDataPoint(long downloadBytes, long uploadBytes) {
		downloadData.add(downloadBytes);
		uploadData.add(uploadBytes);
		while (downloadData.size() > MAX_DATA_POINTS) {
			downloadData.remove(0);
		}
		while (uploadData.size() > MAX_DATA_POINTS) {
			uploadData.remove(0);
		}
		currentDownload = downloadBytes;
		currentUpload = uploadBytes;
		totalDownload += downloadBytes;
		totalUpload += uploadBytes;
		updateMaxValue();
		invalidate();
	}

	
	public void setTotals(long totalDown, long totalUp) {
		this.totalDownload = totalDown;
		this.totalUpload = totalUp;
		invalidate();
	}

	private void updateMaxValue() {
		long max = 1024;
		for (Long value : downloadData) {
			max = Math.max(max, value);
		}
		for (Long value : uploadData) {
			max = Math.max(max, value);
		}
		maxValue = roundToNiceValue((long) (max * 1.2));
	}

	private long roundToNiceValue(long value) {
		if (value <= 1024) return 1024;
		if (value <= 10 * 1024) return 10 * 1024;
		if (value <= 100 * 1024) return 100 * 1024;
		if (value <= 1024 * 1024) return 1024 * 1024;
		if (value <= 10 * 1024 * 1024) return 10 * 1024 * 1024;
		return 100 * 1024 * 1024;
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		canvas.drawRoundRect(backgroundRect, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint);
		drawGrid(canvas);
		drawDataPaths(canvas);
		drawLegend(canvas);
	}

	private void drawGrid(Canvas canvas) {
		float graphHeight = graphRect.height();
		float graphWidth = graphRect.width();
		for (int i = 0; i <= GRID_LINE_COUNT; i++) {
			float y = graphRect.top + (graphHeight / GRID_LINE_COUNT) * i;
			canvas.drawLine(graphRect.left, y, graphRect.right, y, gridPaint);
			long labelValue = maxValue - (long) ((maxValue / GRID_LINE_COUNT) * i);
			String label = formatBandwidth(labelValue);
			float labelY = y + labelPaint.getTextSize() / 3;
			canvas.drawText(label, graphRect.left - 8, labelY, labelPaint);
		}
		for (int i = 0; i <= 3; i++) {
			float x = graphRect.left + (graphWidth / 3) * i;
			canvas.drawLine(x, graphRect.top, x, graphRect.bottom, gridPaint);
		}
	}

	private void drawDataPaths(Canvas canvas) {
		if (downloadData.isEmpty()) return;

		int dataSize = downloadData.size();
		float graphWidth = graphRect.width();
		float graphHeight = graphRect.height();
		float pointSpacing = graphWidth / (MAX_DATA_POINTS - 1);
		downloadPath.reset();
		downloadFillPath.reset();

		boolean firstPoint = true;
		float startX = graphRect.left;

		for (int i = 0; i < dataSize; i++) {
			float x = startX + (i * pointSpacing);
			float value = downloadData.get(i);
			float normalizedValue = maxValue > 0 ? value / (float) maxValue : 0;
			float y = graphRect.bottom - (normalizedValue * graphHeight);

			if (firstPoint) {
				downloadPath.moveTo(x, y);
				downloadFillPath.moveTo(x, graphRect.bottom);
				downloadFillPath.lineTo(x, y);
				firstPoint = false;
			} else {
				downloadPath.lineTo(x, y);
				downloadFillPath.lineTo(x, y);
			}
		}
		downloadFillPath.lineTo(startX + ((dataSize - 1) * pointSpacing), graphRect.bottom);
		downloadFillPath.close();
		uploadPath.reset();
		uploadFillPath.reset();

		firstPoint = true;
		for (int i = 0; i < dataSize; i++) {
			float x = startX + (i * pointSpacing);
			float value = uploadData.get(i);
			float normalizedValue = maxValue > 0 ? value / (float) maxValue : 0;
			float y = graphRect.bottom - (normalizedValue * graphHeight);

			if (firstPoint) {
				uploadPath.moveTo(x, y);
				uploadFillPath.moveTo(x, graphRect.bottom);
				uploadFillPath.lineTo(x, y);
				firstPoint = false;
			} else {
				uploadPath.lineTo(x, y);
				uploadFillPath.lineTo(x, y);
			}
		}
		uploadFillPath.lineTo(startX + ((dataSize - 1) * pointSpacing), graphRect.bottom);
		uploadFillPath.close();
		canvas.drawPath(downloadFillPath, downloadFillPaint);
		canvas.drawPath(uploadFillPath, uploadFillPaint);
		canvas.drawPath(uploadPath, uploadLinePaint);
		canvas.drawPath(downloadPath, downloadLinePaint);
		if (dataSize > 0) {
			float lastX = startX + ((dataSize - 1) * pointSpacing);
			float downloadNorm = maxValue > 0 ? currentDownload / (float) maxValue : 0;
			float downloadY = graphRect.bottom - (downloadNorm * graphHeight);
			drawPulseDot(canvas, lastX, downloadY, COLOR_DOWNLOAD);
			float uploadNorm = maxValue > 0 ? currentUpload / (float) maxValue : 0;
			float uploadY = graphRect.bottom - (uploadNorm * graphHeight);
			drawPulseDot(canvas, lastX, uploadY, COLOR_UPLOAD);
		}
	}

	private void drawPulseDot(Canvas canvas, float x, float y, @ColorInt int color) {
		float density = getResources().getDisplayMetrics().density;
		Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		pulsePaint.setStyle(Paint.Style.FILL);
		int alpha = (int) (pulseAlpha * 80);
		pulsePaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
		canvas.drawCircle(x, y, 8 * density * pulseAlpha, pulsePaint);
		Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		dotPaint.setStyle(Paint.Style.FILL);
		dotPaint.setColor(color);
		canvas.drawCircle(x, y, 4 * density, dotPaint);
		dotPaint.setColor(0xFFFFFFFF);
		canvas.drawCircle(x, y, 2 * density, dotPaint);
	}

	private void drawLegend(Canvas canvas) {
		float density = getResources().getDisplayMetrics().density;
		float y = getHeight() - graphPaddingBottom - legendHeight / 2 + 4 * density;
		float dotRadius = 5 * density;
		float textOffset = 12 * density;
		float sectionWidth = (getWidth() - graphPaddingLeft - graphPaddingRight) / 2;
		float downloadX = graphPaddingLeft;
		Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		dotPaint.setStyle(Paint.Style.FILL);
		dotPaint.setColor(COLOR_DOWNLOAD);
		canvas.drawCircle(downloadX + dotRadius, y, dotRadius, dotPaint);

		legendPaint.setColor(COLOR_LABEL);
		canvas.drawText("Download", downloadX + textOffset + dotRadius, y + 4 * density, legendPaint);

		legendPaint.setColor(COLOR_VALUE);
		String downloadValue = formatBandwidth(currentDownload) + "/s";
		float downloadValueX = downloadX + sectionWidth - legendPaint.measureText(downloadValue) - 8 * density;
		canvas.drawText(downloadValue, downloadValueX, y + 4 * density, legendPaint);
		float uploadX = graphPaddingLeft + sectionWidth;
		dotPaint.setColor(COLOR_UPLOAD);
		canvas.drawCircle(uploadX + dotRadius, y, dotRadius, dotPaint);

		legendPaint.setColor(COLOR_LABEL);
		canvas.drawText("Upload", uploadX + textOffset + dotRadius, y + 4 * density, legendPaint);

		legendPaint.setColor(COLOR_VALUE);
		String uploadValue = formatBandwidth(currentUpload) + "/s";
		float uploadValueX = uploadX + sectionWidth - legendPaint.measureText(uploadValue) - 8 * density;
		canvas.drawText(uploadValue, uploadValueX, y + 4 * density, legendPaint);
	}

	
	private String formatBandwidth(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		} else if (bytes < 1024 * 1024) {
			return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
		} else if (bytes < 1024 * 1024 * 1024) {
			return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
		} else {
			return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
	}

	
	public String getFormattedTotalDownload() {
		return formatBandwidth(totalDownload);
	}

	
	public String getFormattedTotalUpload() {
		return formatBandwidth(totalUpload);
	}

	
	public void clearData() {
		downloadData.clear();
		uploadData.clear();
		for (int i = 0; i < MAX_DATA_POINTS; i++) {
			downloadData.add(0L);
			uploadData.add(0L);
		}
		currentDownload = 0;
		currentUpload = 0;
		totalDownload = 0;
		totalUpload = 0;
		maxValue = 1024;
		invalidate();
	}
}
