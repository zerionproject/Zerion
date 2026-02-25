package com.professor.zerion.android.conversation.voice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/**
 * H.264 video encoder using MediaCodec.
 * Encodes camera frames at 320x240 @15fps for Tor-friendly bandwidth.
 */
@NotNullByDefault
class VideoEncoder {

	static final int WIDTH = 320;
	static final int HEIGHT = 240;
	static final int FRAME_RATE = 15;
	static final int BIT_RATE = 250_000; // 250 kbps
	static final int I_FRAME_INTERVAL = 2; // seconds

	@Nullable
	private MediaCodec encoder;
	@Nullable
	private Surface inputSurface;
	private volatile boolean running = false;
	@Nullable
	private EncodedFrameCallback callback;

	interface EncodedFrameCallback {
		void onEncodedFrame(byte[] data, int offset, int length,
				long presentationTimeUs, boolean isKeyFrame);
	}

	void setCallback(@Nullable EncodedFrameCallback callback) {
		this.callback = callback;
	}

	Surface start() throws IOException {
		MediaFormat format = MediaFormat.createVideoFormat(
				MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities
						.COLOR_FormatSurface);
		format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
		format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
				I_FRAME_INTERVAL);
		format.setInteger(MediaFormat.KEY_PROFILE,
				MediaCodecInfo.CodecProfileLevel
						.AVCProfileBaseline);
		format.setInteger(MediaFormat.KEY_LEVEL,
				MediaCodecInfo.CodecProfileLevel.AVCLevel13);

		encoder = MediaCodec.createEncoderByType(
				MediaFormat.MIMETYPE_VIDEO_AVC);
		encoder.configure(format, null, null,
				MediaCodec.CONFIGURE_FLAG_ENCODE);
		inputSurface = encoder.createInputSurface();
		encoder.start();
		running = true;

		Thread drainThread = new Thread(this::drainEncoder,
				"VideoEncoder-Drain");
		drainThread.setDaemon(true);
		drainThread.start();

		return inputSurface;
	}

	private void drainEncoder() {
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		while (running && encoder != null) {
			int outputIndex = encoder.dequeueOutputBuffer(info, 10000);
			if (outputIndex >= 0) {
				ByteBuffer outputBuffer =
						encoder.getOutputBuffer(outputIndex);
				if (outputBuffer != null && info.size > 0 &&
						callback != null) {
					boolean isKeyFrame = (info.flags &
							MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
					byte[] data = new byte[info.size];
					outputBuffer.position(info.offset);
					outputBuffer.limit(info.offset + info.size);
					outputBuffer.get(data);
					callback.onEncodedFrame(data, 0, data.length,
							info.presentationTimeUs, isKeyFrame);
				}
				encoder.releaseOutputBuffer(outputIndex, false);
			}
		}
	}

	void requestKeyFrame() {
		if (encoder != null) {
			android.os.Bundle params = new android.os.Bundle();
			params.putInt(
					MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
			encoder.setParameters(params);
		}
	}

	void stop() {
		running = false;
		if (encoder != null) {
			try {
				encoder.stop();
			} catch (Exception ignored) {}
			try {
				encoder.release();
			} catch (Exception ignored) {}
			encoder = null;
		}
		if (inputSurface != null) {
			inputSurface.release();
			inputSurface = null;
		}
	}

	@Nullable
	Surface getInputSurface() {
		return inputSurface;
	}

	boolean isRunning() {
		return running;
	}
}
