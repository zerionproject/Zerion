package com.professor.zerion.android.conversation.voice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

@NotNullByDefault
class VideoEncoder {

	static final int WIDTH = 640;
	static final int HEIGHT = 480;
	static final int FRAME_RATE = 24;
	static final int BIT_RATE = 600_000;
	static final int I_FRAME_INTERVAL = 3;

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
						.AVCProfileMain);
		format.setInteger(MediaFormat.KEY_LEVEL,
				MediaCodecInfo.CodecProfileLevel.AVCLevel31);

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
			try {
				int outputIndex = encoder.dequeueOutputBuffer(
						info, 10000);
				if (outputIndex >= 0) {
					boolean isEos = (info.flags
							& MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
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
					if (isEos) break;
				}
			} catch (Exception e) {
				break;
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
