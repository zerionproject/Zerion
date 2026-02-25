package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * Manages camera capture for video calls using Camera2 API.
 * Outputs frames to a Surface provided by the VideoEncoder.
 */
@NotNullByDefault
class VideoCameraManager {

	interface CameraErrorCallback {
		void onCameraError(String reason);
	}

	private boolean useFrontCamera = true;
	private int sensorOrientation = 0;
	@Nullable
	private CameraDevice cameraDevice;
	@Nullable
	private CameraCaptureSession captureSession;
	@Nullable
	private HandlerThread cameraThread;
	@Nullable
	private Handler cameraHandler;
	@Nullable
	private CameraErrorCallback errorCallback;
	@Nullable
	private Surface previewSurface;

	void setErrorCallback(@Nullable CameraErrorCallback callback) {
		this.errorCallback = callback;
	}

	void setPreviewSurface(@Nullable Surface surface) {
		this.previewSurface = surface;
	}

	void start(Context context, Surface encoderSurface) {
		cameraThread = new HandlerThread("CameraThread");
		cameraThread.start();
		cameraHandler = new Handler(cameraThread.getLooper());
		openCamera(context, encoderSurface);
	}

	private void openCamera(Context context, Surface encoderSurface) {
		CameraManager manager = (CameraManager) context.getSystemService(
				Context.CAMERA_SERVICE);
		if (manager == null) return;

		try {
			String cameraId = findCameraId(manager);
			if (cameraId == null) return;

			manager.openCamera(cameraId, new CameraDevice.StateCallback() {
				@Override
				public void onOpened(CameraDevice camera) {
					cameraDevice = camera;
					createCaptureSession(camera, encoderSurface);
				}

				@Override
				public void onDisconnected(CameraDevice camera) {
					camera.close();
					cameraDevice = null;
				}

				@Override
				public void onError(CameraDevice camera, int error) {
					camera.close();
					cameraDevice = null;
					if (errorCallback != null) {
						errorCallback.onCameraError(
								"Camera device error: " + error);
					}
				}
			}, cameraHandler);
		} catch (SecurityException e) {
			if (errorCallback != null) {
				errorCallback.onCameraError(
						"Camera permission denied by system");
			}
		} catch (CameraAccessException e) {
			if (errorCallback != null) {
				errorCallback.onCameraError("Camera not available");
			}
		}
	}

	@Nullable
	private String findCameraId(CameraManager manager)
			throws CameraAccessException {
		int facing = useFrontCamera ?
				CameraCharacteristics.LENS_FACING_FRONT :
				CameraCharacteristics.LENS_FACING_BACK;
		for (String id : manager.getCameraIdList()) {
			CameraCharacteristics chars =
					manager.getCameraCharacteristics(id);
			Integer lensFacing = chars.get(
					CameraCharacteristics.LENS_FACING);
			if (lensFacing != null && lensFacing == facing) {
				Integer orientation = chars.get(
						CameraCharacteristics.SENSOR_ORIENTATION);
				if (orientation != null) {
					sensorOrientation = orientation;
				}
				return id;
			}
		}
		String[] ids = manager.getCameraIdList();
		return ids.length > 0 ? ids[0] : null;
	}

	private void createCaptureSession(CameraDevice camera,
			Surface encoderSurface) {
		boolean usePreview = previewSurface != null
				&& previewSurface.isValid();
		createCaptureSessionInternal(camera, encoderSurface, usePreview);
	}

	private void createCaptureSessionInternal(CameraDevice camera,
			Surface encoderSurface, boolean includePreview) {
		try {
			java.util.List<Surface> targets = new java.util.ArrayList<>();
			targets.add(encoderSurface);
			if (includePreview && previewSurface != null
					&& previewSurface.isValid()) {
				targets.add(previewSurface);
			}
			final boolean triedPreview = includePreview
					&& previewSurface != null;
			camera.createCaptureSession(targets,
					new CameraCaptureSession.StateCallback() {
						@Override
						public void onConfigured(
								CameraCaptureSession session) {
							captureSession = session;
							startPreview(session, camera,
									encoderSurface, triedPreview);
						}

						@Override
						public void onConfigureFailed(
								CameraCaptureSession session) {
							if (triedPreview) {
								// Fallback: retry encoder-only
								createCaptureSessionInternal(
										camera, encoderSurface,
										false);
							} else if (errorCallback != null) {
								errorCallback.onCameraError(
										"Camera session config failed");
							}
						}
					}, cameraHandler);
		} catch (CameraAccessException e) {
			if (errorCallback != null) {
				errorCallback.onCameraError(
						"Camera not available for session");
			}
		}
	}

	private void startPreview(CameraCaptureSession session,
			CameraDevice camera, Surface encoderSurface,
			boolean includePreview) {
		try {
			CaptureRequest.Builder builder =
					camera.createCaptureRequest(
							CameraDevice.TEMPLATE_RECORD);
			builder.addTarget(encoderSurface);
			if (includePreview && previewSurface != null
					&& previewSurface.isValid()) {
				builder.addTarget(previewSurface);
			}
			builder.set(CaptureRequest.CONTROL_AF_MODE,
					CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
			session.setRepeatingRequest(builder.build(),
					null, cameraHandler);
		} catch (CameraAccessException e) {
			if (errorCallback != null) {
				errorCallback.onCameraError("Camera preview failed");
			}
		}
	}

	void switchCamera(Context context, Surface encoderSurface) {
		useFrontCamera = !useFrontCamera;
		stopCamera();
		openCamera(context, encoderSurface);
	}

	private void stopCamera() {
		if (captureSession != null) {
			try {
				captureSession.stopRepeating();
			} catch (Exception ignored) {}
			try {
				captureSession.close();
			} catch (Exception ignored) {}
			captureSession = null;
		}
		if (cameraDevice != null) {
			try {
				cameraDevice.close();
			} catch (Exception ignored) {}
			cameraDevice = null;
		}
	}

	void stop() {
		stopCamera();
		try { Thread.sleep(200); } catch (InterruptedException ignored) {}
		if (cameraThread != null) {
			cameraThread.quitSafely();
			try {
				cameraThread.join(1000);
			} catch (InterruptedException ignored) {}
			cameraThread = null;
		}
		cameraHandler = null;
	}

	boolean isFrontCamera() {
		return useFrontCamera;
	}

	int getSensorOrientation() {
		return sensorOrientation;
	}
}
