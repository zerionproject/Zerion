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

/**
 * A professional real-time network bandwidth graph view.
 * Displays upload and download speeds with smooth animations and gradients.
 */
public class NetworkGraphView extends View {

	// Graph configuration
	private static final int MAX_DATA_POINTS = 60; // 60 seconds of history
	private static final float GRID_LINE_COUNT = 4;
	private static final float CORNER_RADIUS = 16f;

	// Colors
	@ColorInt private static final int COLOR_DOWNLOAD = 0xFF26B7F0; // Zerion cyan
	@ColorInt private static final int COLOR_UPLOAD = 0xFF4CAF50; // Green
	@ColorInt private static final int COLOR_GRID = 0x33FFFFFF;
	@ColorInt private static final int COLOR_BACKGROUND = 0xFF1A1A1A;
	@ColorInt private static final int COLOR_LABEL = 0xFFAAAAAA;
	@ColorInt private static final int COLOR_VALUE = 0xFFFFFFFF;

	// Paints
	private final Paint downloadLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint uploadLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint downloadFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint uploadFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	// Paths for drawing
	private final Path downloadPath = new Path();
	private final Path uploadPath = new Path();
	private final Path downloadFillPath = new Path();
	private final Path uploadFillPath = new Path();
	private final RectF backgroundRect = new RectF();
	private final RectF graphRect = new RectF();

	// Data storage
	private final List<Long> downloadData = new ArrayList<>();
	private final List<Long> uploadData = new ArrayList<>();
	private long maxValue = 1024; // Start with 1 KB/s as minimum scale

	// Current values for display
	private long currentDownload = 0;
	private long currentUpload = 0;
	private long totalDownload = 0;
	private long totalUpload = 0;

	// Animation
	private ValueAnimator pulseAnimator;
	private float pulseAlpha = 1f;

	// Dimensions
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

		// Graph padding
		graphPaddingLeft = 56 * density;
		graphPaddingRight = 16 * density;
		graphPaddingTop = 16 * density;
		graphPaddingBottom = 24 * density;
		legendHeight = 48 * density;

		// Download line paint
		downloadLinePaint.setStyle(Paint.Style.STROKE);
		downloadLinePaint.setStrokeWidth(2.5f * density);
		downloadLinePaint.setColor(COLOR_DOWNLOAD);
		downloadLinePaint.setStrokeCap(Paint.Cap.ROUND);
		downloadLinePaint.setStrokeJoin(Paint.Join.ROUND);

		// Upload line paint
		uploadLinePaint.setStyle(Paint.Style.STROKE);
		uploadLinePaint.setStrokeWidth(2.5f * density);
		uploadLinePaint.setColor(COLOR_UPLOAD);
		uploadLinePaint.setStrokeCap(Paint.Cap.ROUND);
		uploadLinePaint.setStrokeJoin(Paint.Join.ROUND);

		// Fill paints (will have gradient set in onSizeChanged)
		downloadFillPaint.setStyle(Paint.Style.FILL);
		uploadFillPaint.setStyle(Paint.Style.FILL);

		// Grid paint
		gridPaint.setStyle(Paint.Style.STROKE);
		gridPaint.setStrokeWidth(1f);
		gridPaint.setColor(COLOR_GRID);

		// Background paint
		backgroundPaint.setStyle(Paint.Style.FILL);
		backgroundPaint.setColor(COLOR_BACKGROUND);

		// Label paint
		labelPaint.setTextSize(11 * density);
		labelPaint.setColor(COLOR_LABEL);
		labelPaint.setTextAlign(Paint.Align.RIGHT);

		// Value paint
		valuePaint.setTextSize(13 * density);
		valuePaint.setColor(COLOR_VALUE);
		valuePaint.setTextAlign(Paint.Align.LEFT);

		// Legend paint
		legendPaint.setTextSize(12 * density);
		legendPaint.setTextAlign(Paint.Align.LEFT);

		// Initialize with empty data
		for (int i = 0; i < MAX_DATA_POINTS; i++) {
			downloadData.add(0L);
			uploadData.add(0L);
		}

		// Start pulse animation
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

		// Update background rect
		backgroundRect.set(0, 0, w, h);

		// Update graph rect (area where the actual graph is drawn)
		float graphBottom = h - graphPaddingBottom - legendHeight;
		graphRect.set(graphPaddingLeft, graphPaddingTop, w - graphPaddingRight, graphBottom);

		// Update fill gradients
		updateGradients();
	}

	private void updateGradients() {
		if (graphRect.height() <= 0) return;

		// Download gradient (cyan to transparent)
		LinearGradient downloadGradient = new LinearGradient(
				0, graphRect.top,
				0, graphRect.bottom,
				new int[]{
						(COLOR_DOWNLOAD & 0x00FFFFFF) | 0x40000000, // 25% alpha
						(COLOR_DOWNLOAD & 0x00FFFFFF) | 0x08000000  // 3% alpha
				},
				new float[]{0f, 1f},
				Shader.TileMode.CLAMP
		);
		downloadFillPaint.setShader(downloadGradient);

		// Upload gradient (green to transparent)
		LinearGradient uploadGradient = new LinearGradient(
				0, graphRect.top,
				0, graphRect.bottom,
				new int[]{
						(COLOR_UPLOAD & 0x00FFFFFF) | 0x30000000, // 19% alpha
						(COLOR_UPLOAD & 0x00FFFFFF) | 0x08000000  // 3% alpha
				},
				new float[]{0f, 1f},
				Shader.TileMode.CLAMP
		);
		uploadFillPaint.setShader(uploadGradient);
	}

	/**
	 * Add new bandwidth data points.
	 *
	 * @param downloadBytes Download speed in bytes per second
	 * @param uploadBytes   Upload speed in bytes per second
	 */
	public void addDataPoint(long downloadBytes, long uploadBytes) {
		// Add new data points
		downloadData.add(downloadBytes);
		uploadData.add(uploadBytes);

		// Remove oldest data points if we exceed max
		while (downloadData.size() > MAX_DATA_POINTS) {
			downloadData.remove(0);
		}
		while (uploadData.size() > MAX_DATA_POINTS) {
			uploadData.remove(0);
		}

		// Update current values
		currentDownload = downloadBytes;
		currentUpload = uploadBytes;

		// Update totals
		totalDownload += downloadBytes;
		totalUpload += uploadBytes;

		// Update max value for scaling
		updateMaxValue();

		// Trigger redraw
		invalidate();
	}

	/**
	 * Set total bandwidth values.
	 */
	public void setTotals(long totalDown, long totalUp) {
		this.totalDownload = totalDown;
		this.totalUpload = totalUp;
		invalidate();
	}

	private void updateMaxValue() {
		long max = 1024; // Minimum 1 KB/s scale
		for (Long value : downloadData) {
			max = Math.max(max, value);
		}
		for (Long value : uploadData) {
			max = Math.max(max, value);
		}
		// Add 20% headroom and round to nice value
		maxValue = roundToNiceValue((long) (max * 1.2));
	}

	private long roundToNiceValue(long value) {
		if (value <= 1024) return 1024; // 1 KB/s
		if (value <= 10 * 1024) return 10 * 1024; // 10 KB/s
		if (value <= 100 * 1024) return 100 * 1024; // 100 KB/s
		if (value <= 1024 * 1024) return 1024 * 1024; // 1 MB/s
		if (value <= 10 * 1024 * 1024) return 10 * 1024 * 1024; // 10 MB/s
		return 100 * 1024 * 1024; // 100 MB/s
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);

		// Draw rounded background
		canvas.drawRoundRect(backgroundRect, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint);

		// Draw grid lines and labels
		drawGrid(canvas);

		// Draw data paths
		drawDataPaths(canvas);

		// Draw legend
		drawLegend(canvas);
	}

	private void drawGrid(Canvas canvas) {
		float graphHeight = graphRect.height();
		float graphWidth = graphRect.width();

		// Horizontal grid lines
		for (int i = 0; i <= GRID_LINE_COUNT; i++) {
			float y = graphRect.top + (graphHeight / GRID_LINE_COUNT) * i;
			canvas.drawLine(graphRect.left, y, graphRect.right, y, gridPaint);

			// Draw labels
			long labelValue = maxValue - (long) ((maxValue / GRID_LINE_COUNT) * i);
			String label = formatBandwidth(labelValue);
			float labelY = y + labelPaint.getTextSize() / 3;
			canvas.drawText(label, graphRect.left - 8, labelY, labelPaint);
		}

		// Vertical grid lines (time markers)
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

		// Build download path
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

		// Close fill path
		downloadFillPath.lineTo(startX + ((dataSize - 1) * pointSpacing), graphRect.bottom);
		downloadFillPath.close();

		// Build upload path
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

		// Close fill path
		uploadFillPath.lineTo(startX + ((dataSize - 1) * pointSpacing), graphRect.bottom);
		uploadFillPath.close();

		// Draw fills first (behind lines)
		canvas.drawPath(downloadFillPath, downloadFillPaint);
		canvas.drawPath(uploadFillPath, uploadFillPaint);

		// Draw lines
		canvas.drawPath(uploadPath, uploadLinePaint);
		canvas.drawPath(downloadPath, downloadLinePaint);

		// Draw current value dots with pulse animation
		if (dataSize > 0) {
			float lastX = startX + ((dataSize - 1) * pointSpacing);

			// Download dot
			float downloadNorm = maxValue > 0 ? currentDownload / (float) maxValue : 0;
			float downloadY = graphRect.bottom - (downloadNorm * graphHeight);
			drawPulseDot(canvas, lastX, downloadY, COLOR_DOWNLOAD);

			// Upload dot
			float uploadNorm = maxValue > 0 ? currentUpload / (float) maxValue : 0;
			float uploadY = graphRect.bottom - (uploadNorm * graphHeight);
			drawPulseDot(canvas, lastX, uploadY, COLOR_UPLOAD);
		}
	}

	private void drawPulseDot(Canvas canvas, float x, float y, @ColorInt int color) {
		float density = getResources().getDisplayMetrics().density;

		// Outer pulse ring
		Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		pulsePaint.setStyle(Paint.Style.FILL);
		int alpha = (int) (pulseAlpha * 80);
		pulsePaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
		canvas.drawCircle(x, y, 8 * density * pulseAlpha, pulsePaint);

		// Inner solid dot
		Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		dotPaint.setStyle(Paint.Style.FILL);
		dotPaint.setColor(color);
		canvas.drawCircle(x, y, 4 * density, dotPaint);

		// White center
		dotPaint.setColor(0xFFFFFFFF);
		canvas.drawCircle(x, y, 2 * density, dotPaint);
	}

	private void drawLegend(Canvas canvas) {
		float density = getResources().getDisplayMetrics().density;
		float y = getHeight() - graphPaddingBottom - legendHeight / 2 + 4 * density;
		float dotRadius = 5 * density;
		float textOffset = 12 * density;
		float sectionWidth = (getWidth() - graphPaddingLeft - graphPaddingRight) / 2;

		// Download legend
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

		// Upload legend
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

	/**
	 * Format bandwidth value to human-readable string.
	 */
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

	/**
	 * Get formatted total download.
	 */
	public String getFormattedTotalDownload() {
		return formatBandwidth(totalDownload);
	}

	/**
	 * Get formatted total upload.
	 */
	public String getFormattedTotalUpload() {
		return formatBandwidth(totalUpload);
	}

	/**
	 * Clear all data and reset the graph.
	 */
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
