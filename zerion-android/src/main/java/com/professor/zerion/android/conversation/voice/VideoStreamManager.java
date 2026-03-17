package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.view.Surface;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
class VideoStreamManager {

	private static final int VIDEO_SYNC_MARKER = 0x5A564944;
	private static final int VIDEO_FRAME_MARKER = 0x56464D00;
	private static final int GCM_TAG_LENGTH = 128;
	private static final int NONCE_LENGTH = 12;
	private static final int PAD_BOUNDARY = 512;

	private final VideoEncoder encoder = new VideoEncoder();
	private final VideoDecoder decoder = new VideoDecoder();
	private final VideoCameraManager camera = new VideoCameraManager();

	@Nullable
	private byte[] txKeyBytes;
	@Nullable
	private byte[] rxKeyBytes;
	private final AtomicLong txFrameCounter = new AtomicLong(0);
	private final AtomicLong rxFrameCounter = new AtomicLong(0);

	@Nullable
	private DataOutputStream videoOut;
	@Nullable
	private DataInputStream videoIn;
	private volatile boolean running = false;
	private volatile boolean sendingPaused = false;
	@Nullable
	private Thread receiveThread;
	@Nullable
	private VideoStateCallback stateCallback;
	@Nullable
	private VideoRotationCallback rotationCallback;
	private int consecutiveAuthFailures = 0;

	interface VideoStateCallback {
		void onVideoStarted();
		void onVideoStopped();
		void onVideoError(String reason);
	}

	interface VideoRotationCallback {
		void onRemoteRotation(int degrees);
	}

	void setStateCallback(@Nullable VideoStateCallback callback) {
		this.stateCallback = callback;
	}

	void setRotationCallback(@Nullable VideoRotationCallback callback) {
		this.rotationCallback = callback;
	}

	void setCameraReadyCallback(
			@Nullable VideoCameraManager.CameraReadyCallback callback) {
		camera.setCameraReadyCallback(callback);
	}

	void updatePreviewSurface(Surface surface) {
		camera.updatePreviewSurface(surface);
	}

	void initKeys(byte[] videoTxKey, byte[] videoRxKey) {
		this.txKeyBytes = Arrays.copyOf(videoTxKey, videoTxKey.length);
		this.rxKeyBytes = Arrays.copyOf(videoRxKey, videoRxKey.length);
	}

	void startSending(Context context, OutputStream outputStream)
			throws IOException {
		if (txKeyBytes == null) {
			throw new IOException("Video TX key not initialized");
		}
		videoOut = new DataOutputStream(outputStream);
		running = true;

		Surface encoderSurface = encoder.start();

		encoder.setCallback((data, offset, length, pts, isKeyFrame) -> {
			if (!running || sendingPaused || videoOut == null) return;
			try {
				sendEncryptedFrame(data, offset, length, pts,
						isKeyFrame);
			} catch (Exception e) {
				if (running && stateCallback != null) {
					stateCallback.onVideoError("Send failed");
				}
			}
		});

		camera.setErrorCallback(reason -> {
			if (running && stateCallback != null) {
				stateCallback.onVideoError(reason);
			}
		});

		camera.start(context, encoderSurface);

		videoOut.writeInt(VIDEO_SYNC_MARKER);
		videoOut.flush();

		if (stateCallback != null) {
			stateCallback.onVideoStarted();
		}
	}

	void startReceiving(InputStream inputStream,
			Surface decoderOutputSurface) throws IOException {
		if (rxKeyBytes == null) {
			throw new IOException("Video RX key not initialized");
		}
		videoIn = new DataInputStream(inputStream);
		decoder.start(decoderOutputSurface);

		receiveThread = new Thread(() -> {
			try {
				receiveLoop();
			} catch (IOException e) {
				if (running && stateCallback != null) {
					stateCallback.onVideoError("Receive failed");
				}
			}
		}, "VideoStream-Receive");
		receiveThread.setDaemon(true);
		receiveThread.start();
	}

	private static final int INNER_META_SIZE = 14;

	private void sendEncryptedFrame(byte[] data, int offset, int length,
			long presentationTimeUs, boolean isKeyFrame) throws Exception {
		if (videoOut == null || txKeyBytes == null) return;

		int totalPlain = INNER_META_SIZE + length;
		int paddedLength = ((totalPlain + PAD_BOUNDARY - 1) / PAD_BOUNDARY)
				* PAD_BOUNDARY;
		byte[] padded = new byte[paddedLength];
		padded[0] = (byte) ((length >> 24) & 0xFF);
		padded[1] = (byte) ((length >> 16) & 0xFF);
		padded[2] = (byte) ((length >> 8) & 0xFF);
		padded[3] = (byte) (length & 0xFF);
		for (int i = 0; i < 8; i++) {
			padded[4 + i] = (byte) ((presentationTimeUs >>
					(56 - i * 8)) & 0xFF);
		}
		padded[12] = (byte) (isKeyFrame ? 1 : 0);
		padded[13] = (byte) (camera.getSensorOrientation() / 90);
		System.arraycopy(data, offset, padded, INNER_META_SIZE, length);

		long counter = txFrameCounter.getAndIncrement();
		byte[] nonce = buildNonce(txKeyBytes, counter);
		byte[] encrypted = encryptGCM(padded, txKeyBytes, nonce);

		synchronized (videoOut) {
			videoOut.writeInt(VIDEO_FRAME_MARKER);
			videoOut.writeInt(encrypted.length);
			videoOut.write(encrypted);
			videoOut.flush();
		}

		Arrays.fill(padded, (byte) 0);
	}

	private void receiveLoop() throws IOException {
		if (videoIn == null) return;

		int sync = videoIn.readInt();
		if (sync != VIDEO_SYNC_MARKER) {
			throw new IOException("Invalid video sync marker");
		}

		while (running) {
			int marker = videoIn.readInt();
			if (marker != VIDEO_FRAME_MARKER) continue;

			int encryptedLength = videoIn.readInt();

			if (encryptedLength <= 0 || encryptedLength > 500_000) {
				continue;
			}

			byte[] encrypted = new byte[encryptedLength];
			videoIn.readFully(encrypted);

			try {
				long counter = rxFrameCounter.getAndIncrement();
				byte[] nonce = buildNonce(rxKeyBytes, counter);
				byte[] decrypted = decryptGCM(encrypted, rxKeyBytes,
						nonce);

				if (decrypted == null ||
						decrypted.length < INNER_META_SIZE) {
					continue;
				}

				consecutiveAuthFailures = 0;

				int originalLength = ((decrypted[0] & 0xFF) << 24)
						| ((decrypted[1] & 0xFF) << 16)
						| ((decrypted[2] & 0xFF) << 8)
						| (decrypted[3] & 0xFF);
				long presentationTimeUs = 0;
				for (int i = 0; i < 8; i++) {
					presentationTimeUs |=
							((long) (decrypted[4 + i] & 0xFF))
									<< (56 - i * 8);
				}

				int remoteDegrees = (decrypted[13] & 0xFF) * 90;
				if (rotationCallback != null) {
					rotationCallback.onRemoteRotation(remoteDegrees);
				}

				if (originalLength > 0 && INNER_META_SIZE
						+ originalLength <= decrypted.length) {
					decoder.decodeFrame(decrypted, INNER_META_SIZE,
							originalLength, presentationTimeUs);
				}
			} catch (javax.crypto.AEADBadTagException e) {
				consecutiveAuthFailures++;
				if (consecutiveAuthFailures >= 3
						&& stateCallback != null) {
					stateCallback.onVideoError(
							"Video stream integrity failure");
					return;
				}
			} catch (Exception e) {
				consecutiveAuthFailures = 0;
			}
		}
	}

	private static byte[] buildNonce(byte[] keyBytes, long counter) {
		byte[] nonce = new byte[NONCE_LENGTH];
		try {
			java.security.MessageDigest md =
					java.security.MessageDigest.getInstance("SHA-256");
			md.update("VIDEO_NONCE_SALT".getBytes(
					java.nio.charset.StandardCharsets.UTF_8));
			md.update(keyBytes);
			byte[] counterBytes = new byte[8];
			for (int i = 7; i >= 0; i--) {
				counterBytes[i] = (byte) (counter & 0xFF);
				counter >>= 8;
			}
			md.update(counterBytes);
			byte[] hash = md.digest();
			System.arraycopy(hash, 0, nonce, 0, NONCE_LENGTH);
			Arrays.fill(hash, (byte) 0);
			Arrays.fill(counterBytes, (byte) 0);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 required", e);
		}
		return nonce;
	}

	@Nullable
	private static byte[] encryptGCM(byte[] plaintext, byte[] keyBytes,
			byte[] nonce) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(
				GCM_TAG_LENGTH, nonce);
		cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
		return cipher.doFinal(plaintext);
	}

	@Nullable
	private static byte[] decryptGCM(byte[] ciphertext, byte[] keyBytes,
			byte[] nonce) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(
				GCM_TAG_LENGTH, nonce);
		cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
		return cipher.doFinal(ciphertext);
	}

	void setPreviewSurface(@Nullable Surface surface) {
		camera.setPreviewSurface(surface);
	}

	void switchCamera(Context context) {
		Surface encoderSurface = encoder.getInputSurface();
		if (encoderSurface != null) {
			camera.switchCamera(context, encoderSurface);
			encoder.requestKeyFrame();
		}
	}

	void requestKeyFrame() {
		encoder.requestKeyFrame();
	}

	void pauseSending() {
		sendingPaused = true;
		camera.stop();
		encoder.stop();
	}

	void resumeSending(Context context) throws IOException {
		if (videoOut == null || txKeyBytes == null) {
			throw new IOException("Cannot resume: stream not initialized");
		}
		sendingPaused = false;

		Surface encoderSurface = encoder.start();

		encoder.setCallback((data, offset, length, pts, isKeyFrame) -> {
			if (!running || sendingPaused || videoOut == null) return;
			try {
				sendEncryptedFrame(data, offset, length, pts,
						isKeyFrame);
			} catch (Exception e) {
				if (running && stateCallback != null) {
					stateCallback.onVideoError("Send failed");
				}
			}
		});

		camera.setErrorCallback(reason -> {
			if (running && stateCallback != null) {
				stateCallback.onVideoError(reason);
			}
		});

		camera.start(context, encoderSurface);
	}

	boolean isSendingPaused() {
		return sendingPaused;
	}

	void stop() {
		running = false;
		sendingPaused = false;

		try { camera.stop(); } catch (Exception ignored) {}
		try { encoder.stop(); } catch (Exception ignored) {}
		try { decoder.stop(); } catch (Exception ignored) {}

		if (receiveThread != null) {
			receiveThread.interrupt();
			receiveThread = null;
		}

		zeroizeKeys();
	}

	private void zeroizeKeys() {
		if (txKeyBytes != null) {
			Arrays.fill(txKeyBytes, (byte) 0);
			txKeyBytes = null;
		}
		if (rxKeyBytes != null) {
			Arrays.fill(rxKeyBytes, (byte) 0);
			rxKeyBytes = null;
		}
	}

	boolean isRunning() {
		return running;
	}

	int getCameraSensorOrientation() {
		return camera.getSensorOrientation();
	}

	boolean isCameraFront() {
		return camera.isFrontCamera();
	}
}
