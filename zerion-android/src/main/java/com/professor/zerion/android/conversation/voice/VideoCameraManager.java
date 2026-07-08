package com.professor.zerion.android.conversation.voice;

import android.content.Context;
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


import javax.annotation.Nullable;

@NotNullByDefault
class VideoCameraManager {

	interface CameraErrorCallback {
		void onCameraError(String reason);
	}

	interface CameraReadyCallback {
		void onCameraReady(int sensorOrientation, boolean isFront);
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
	private CameraReadyCallback readyCallback;
	@Nullable
	private Surface previewSurface;
	@Nullable
	private Surface encoderSurfaceRef;

	@Nullable
	private Context pendingSwitchContext;
	@Nullable
	private Surface pendingSwitchEncoderSurface;

	void setErrorCallback(@Nullable CameraErrorCallback callback) {
		this.errorCallback = callback;
	}

	void setCameraReadyCallback(@Nullable CameraReadyCallback callback) {
		this.readyCallback = callback;
	}

	void setPreviewSurface(@Nullable Surface surface) {
		this.previewSurface = surface;
	}

	void updatePreviewSurface(Surface surface) {
		this.previewSurface = surface;
		if (cameraDevice != null && encoderSurfaceRef != null
				&& cameraHandler != null) {
			final CameraDevice cam = cameraDevice;
			final Surface enc = encoderSurfaceRef;
			cameraHandler.post(() -> {
				if (captureSession != null) {
					try {
						captureSession.stopRepeating();
					} catch (Exception ignored) {}
					try {
						captureSession.close();
					} catch (Exception ignored) {}
					captureSession = null;
				}
				createCaptureSession(cam, enc);
			});
		}
	}

	void start(Context context, Surface encoderSurface) {
		encoderSurfaceRef = encoderSurface;
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
				public void onClosed(CameraDevice camera) {

					if (pendingSwitchContext != null) {
						Context ctx = pendingSwitchContext;
						Surface surf = pendingSwitchEncoderSurface;
						pendingSwitchContext = null;
						pendingSwitchEncoderSurface = null;
						if (surf != null) openCamera(ctx, surf);
					}
				}

				@Override
				public void onError(CameraDevice camera, int error) {
					camera.close();
					cameraDevice = null;
					if (errorCallback != null) {
						errorCallback.onCameraError(describeError(error));
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
			if (readyCallback != null) {
				readyCallback.onCameraReady(sensorOrientation,
						useFrontCamera);
			}
		} catch (Exception e) {
			if (includePreview) {
				startPreview(session, camera, encoderSurface, false);
			} else if (errorCallback != null) {
				errorCallback.onCameraError("Camera preview failed");
			}
		}
	}

	void switchCamera(Context context, Surface encoderSurface) {
		useFrontCamera = !useFrontCamera;

		pendingSwitchContext = context;
		pendingSwitchEncoderSurface = encoderSurface;
		stopCamera();
	}

	private static String describeError(int error) {
		switch (error) {
			case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
				return "Camera already in use by another app";
			case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
				return "Too many cameras open. Close other apps";
			case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
				return "Camera disabled by device policy";
			case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
				return "Camera hardware error";
			case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
				return "Camera service error. Restart the app";
			default:
				return "Camera error (code " + error + ")";
		}
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
		if (cameraThread != null) {
			cameraThread.quitSafely();
			try {
				cameraThread.join(1500);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
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
