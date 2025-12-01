package com.professor.zerion.android.qrcode;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.util.AttributeSet;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import static android.content.Context.WINDOW_SERVICE;
import static android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
import static android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;
import static android.hardware.Camera.Parameters.FLASH_MODE_OFF;
import static android.hardware.Camera.Parameters.FOCUS_MODE_AUTO;
import static android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
import static android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
import static android.hardware.Camera.Parameters.FOCUS_MODE_EDOF;
import static android.hardware.Camera.Parameters.FOCUS_MODE_FIXED;
import static android.hardware.Camera.Parameters.FOCUS_MODE_MACRO;
import static android.hardware.Camera.Parameters.SCENE_MODE_AUTO;
import static android.hardware.Camera.Parameters.SCENE_MODE_BARCODE;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class CameraView extends SurfaceView implements SurfaceHolder.Callback,
		AutoFocusCallback, View.OnClickListener {

	private static final int IDEAL_PIXELS = 500 * 1000;

	private static final int AUTO_FOCUS_RETRY_DELAY = 5000;


	private final Runnable autoFocusRetry = this::retryAutoFocus;

	@Nullable
	private Camera camera = null;
	private int cameraIndex = 0;
	private PreviewConsumer previewConsumer = null;
	private Surface surface = null;
	private int displayOrientation = 0, surfaceWidth = 0, surfaceHeight = 0;
	private boolean previewStarted = false;
	private boolean autoFocusSupported = false, autoFocusRunning = false;

	public CameraView(Context context) {
		super(context);
	}

	public CameraView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public CameraView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@UiThread
	public void setPreviewConsumer(PreviewConsumer previewConsumer) {
		this.previewConsumer = previewConsumer;
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		setKeepScreenOn(true);
		getHolder().addCallback(this);
		setOnClickListener(this);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		setKeepScreenOn(false);
		getHolder().removeCallback(this);
	}

	@UiThread
	public void start() throws CameraException {
		try {
			int cameras = Camera.getNumberOfCameras();
			if (cameras == 0) throw new CameraException("No camera");
			for (int i = 0; i < cameras; i++) {
				CameraInfo info = new CameraInfo();
				Camera.getCameraInfo(i, info);
				if (info.facing == CAMERA_FACING_BACK) {
					camera = Camera.open(i);
					cameraIndex = i;
					break;
				}
			}
			if (camera == null) {
				camera = Camera.open(0);
				cameraIndex = 0;
			}
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		setDisplayOrientation(getScreenRotationDegrees());
		if (camera == null) throw new CameraException("No camera found");
		Parameters params;
		try {
			params = camera.getParameters();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		params = setSceneMode(camera, params);
		if (SCENE_MODE_BARCODE.equals(params.getSceneMode())) {
			if (!FLASH_MODE_OFF.equals(params.getFlashMode()))
				params = disableFlash(camera, params);
			if (!FLASH_MODE_OFF.equals(params.getFlashMode()))
				params = disableSceneMode(camera, params);
		}
		params = setBestParameters(camera, params);
		enableAutoFocus(params.getFocusMode());
		if (surface != null && !previewStarted) startPreview(getHolder());
	}

	@UiThread
	public void stop() throws CameraException {
		if (camera == null) return;
		stopPreview();
		try {
			camera.release();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		camera = null;
	}

	private int getScreenRotationDegrees() {
		WindowManager wm =
				(WindowManager) getContext().getSystemService(WINDOW_SERVICE);
		Display d = wm.getDefaultDisplay();
		switch (d.getRotation()) {
			case Surface.ROTATION_0:
				return 0;
			case Surface.ROTATION_90:
				return 90;
			case Surface.ROTATION_180:
				return 180;
			case Surface.ROTATION_270:
				return 270;
			default:
				throw new AssertionError();
		}
	}

	@UiThread
	private void startPreview(SurfaceHolder holder) throws CameraException {
		if (camera == null) throw new CameraException("Camera is null");
		try {
			camera.setPreviewDisplay(holder);
			camera.startPreview();
			previewStarted = true;
			startConsumer();
		} catch (IOException | RuntimeException e) {
			throw new CameraException(e);
		}
	}

	@UiThread
	private void stopPreview() throws CameraException {
		if (camera == null) throw new CameraException("Camera is null");
		try {
			stopConsumer();
			camera.stopPreview();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		previewStarted = false;
	}

	@UiThread
	private void startConsumer() throws CameraException {
		if (camera == null) throw new CameraException("Camera is null");
		startAutoFocus();
		previewConsumer.start(camera, cameraIndex);
	}

	@UiThread
	private void startAutoFocus() throws CameraException {
		if (camera != null && autoFocusSupported && !autoFocusRunning) {
			try {
				removeCallbacks(autoFocusRetry);
				camera.autoFocus(this);
				autoFocusRunning = true;
			} catch (RuntimeException e) {
				throw new CameraException(e);
			}
		}
	}

	@UiThread
	private void stopConsumer() throws CameraException {
		if (camera == null) throw new CameraException("Camera is null");
		cancelAutoFocus();
		previewConsumer.stop();
	}

	@UiThread
	private void cancelAutoFocus() throws CameraException {
		if (camera != null && autoFocusSupported && autoFocusRunning) {
			try {
				removeCallbacks(autoFocusRetry);
				camera.cancelAutoFocus();
				autoFocusRunning = false;
			} catch (RuntimeException e) {
				throw new CameraException(e);
			}
		}
	}

	@UiThread
	private void setDisplayOrientation(int rotationDegrees)
			throws CameraException {
		if (camera == null) throw new CameraException("Camera is null");
		int orientation;
		CameraInfo info = new CameraInfo();
		try {
			Camera.getCameraInfo(cameraIndex, info);
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		if (info.facing == CAMERA_FACING_FRONT) {
			orientation = (info.orientation + rotationDegrees) % 360;
			orientation = (360 - orientation) % 360;
		} else {
			orientation = (info.orientation - rotationDegrees + 360) % 360;
		}
		try {
			camera.setDisplayOrientation(orientation);
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		displayOrientation = orientation;
	}

	@UiThread
	private Parameters setSceneMode(Camera camera, Parameters params)
			throws CameraException {
		List<String> sceneModes = params.getSupportedSceneModes();
		if (sceneModes == null) return params;
		if (sceneModes.contains(SCENE_MODE_BARCODE)) {
			params.setSceneMode(SCENE_MODE_BARCODE);
			try {
				camera.setParameters(params);
				return camera.getParameters();
			} catch (RuntimeException e) {
				throw new CameraException(e);
			}
		}
		return params;
	}

	@UiThread
	private Parameters disableFlash(Camera camera, Parameters params)
			throws CameraException {
		params.setFlashMode(FLASH_MODE_OFF);
		try {
			camera.setParameters(params);
			return camera.getParameters();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
	}

	@UiThread
	private Parameters disableSceneMode(Camera camera, Parameters params)
			throws CameraException {
		params.setSceneMode(SCENE_MODE_AUTO);
		try {
			camera.setParameters(params);
			return camera.getParameters();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
	}

	@UiThread
	private Parameters setBestParameters(Camera camera, Parameters params)
			throws CameraException {
		setVideoStabilisation(params);
		setFocusMode(params);
		params.setFlashMode(FLASH_MODE_OFF);
		setPreviewSize(params);
		try {
			camera.setParameters(params);
			return camera.getParameters();
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
	}

	@UiThread
	private void setVideoStabilisation(Parameters params) {
		if (params.isVideoStabilizationSupported()) {
			params.setVideoStabilization(true);
		}
	}

	@UiThread
	private void setFocusMode(Parameters params) {
		List<String> focusModes = params.getSupportedFocusModes();
		if (focusModes.contains(FOCUS_MODE_CONTINUOUS_PICTURE)) {
			params.setFocusMode(FOCUS_MODE_CONTINUOUS_PICTURE);
		} else if (focusModes.contains(FOCUS_MODE_CONTINUOUS_VIDEO)) {
			params.setFocusMode(FOCUS_MODE_CONTINUOUS_VIDEO);
		} else if (focusModes.contains(FOCUS_MODE_EDOF)) {
			params.setFocusMode(FOCUS_MODE_EDOF);
		} else if (focusModes.contains(FOCUS_MODE_MACRO)) {
			params.setFocusMode(FOCUS_MODE_MACRO);
		} else if (focusModes.contains(FOCUS_MODE_AUTO)) {
			params.setFocusMode(FOCUS_MODE_AUTO);
		} else if (focusModes.contains(FOCUS_MODE_FIXED)) {
			params.setFocusMode(FOCUS_MODE_FIXED);
		}
	}

	@UiThread
	private void setPreviewSize(Parameters params) {
		if (surfaceWidth == 0 || surfaceHeight == 0) return;
		float idealRatio = (float) surfaceWidth / surfaceHeight;
		boolean rotatePreview = displayOrientation % 180 == 90;
		List<Size> sizes = params.getSupportedPreviewSizes();
		Size bestSize = null;
		float bestScore = 0;
		for (Size size : sizes) {
			int width = rotatePreview ? size.height : size.width;
			int height = rotatePreview ? size.width : size.height;
			float ratio = (float) width / height;
			float stretch = Math.max(ratio / idealRatio, idealRatio / ratio);
			float pixels = width * height;
			float zoom = Math.max(pixels / IDEAL_PIXELS, IDEAL_PIXELS / pixels);
			float score = 1 / (stretch * zoom);
			if (bestSize == null || score > bestScore) {
				bestSize = size;
				bestScore = score;
			}
		}
		if (bestSize != null) {
			params.setPreviewSize(bestSize.width, bestSize.height);
		}
	}

	@UiThread
	private void enableAutoFocus(String focusMode) {
		autoFocusSupported = FOCUS_MODE_AUTO.equals(focusMode) ||
				FOCUS_MODE_MACRO.equals(focusMode);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		post(() -> {
			try {
				surfaceCreatedUi(holder);
			} catch (CameraException e) {
				/* silent */
			}
		});
	}

	@UiThread
	private void surfaceCreatedUi(SurfaceHolder holder) throws CameraException {
		if (surface != null && surface != holder.getSurface()) {
			surface.release();
		}
		surface = holder.getSurface();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		post(() -> {
			try {
				surfaceChangedUi(holder, w, h);
			} catch (CameraException e) {
				/* silent */
			}
		});
	}

	@UiThread
	private void surfaceChangedUi(SurfaceHolder holder, int w, int h)
			throws CameraException {
		if (surface != null && surface != holder.getSurface()) {
			surface.release();
		}
		surface = holder.getSurface();
		surfaceWidth = w;
		surfaceHeight = h;
		if (camera == null) return;
		if (previewStarted) stopPreview();
		try {
			Parameters params = camera.getParameters();
			setPreviewSize(params);
			camera.setParameters(params);
		} catch (RuntimeException e) {
			throw new CameraException(e);
		}
		startPreview(holder);
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		post(() -> surfaceDestroyedUi(holder));
	}

	@UiThread
	private void surfaceDestroyedUi(SurfaceHolder holder) {
		if (surface != null && surface != holder.getSurface()) {
			surface.release();
		}
		surface = null;
		holder.getSurface().release();
	}

	@Override
	public void onAutoFocus(boolean success, Camera camera) {
		autoFocusRunning = false;
		postDelayed(autoFocusRetry, AUTO_FOCUS_RETRY_DELAY);
	}

	@UiThread
	private void retryAutoFocus() {
		try {
			startAutoFocus();
		} catch (CameraException e) {
			/* silent */
		}
	}

	@Override
	public void onClick(View v) {
		retryAutoFocus();
	}
}