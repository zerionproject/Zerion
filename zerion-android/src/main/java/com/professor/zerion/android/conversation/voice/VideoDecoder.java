package com.professor.zerion.android.conversation.voice;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

@NotNullByDefault
class VideoDecoder {

	@Nullable
	private MediaCodec decoder;
	private volatile boolean running = false;
	private int decodeFailures = 0;

	void start(Surface outputSurface) throws IOException {
		MediaFormat format = MediaFormat.createVideoFormat(
				MediaFormat.MIMETYPE_VIDEO_AVC,
				VideoEncoder.WIDTH, VideoEncoder.HEIGHT);

		decoder = MediaCodec.createDecoderByType(
				MediaFormat.MIMETYPE_VIDEO_AVC);
		decoder.configure(format, outputSurface, null, 0);
		decoder.start();
		running = true;

		Thread renderThread = new Thread(this::drainDecoder,
				"VideoDecoder-Render");
		renderThread.setDaemon(true);
		renderThread.start();
	}

	void decodeFrame(byte[] data, int offset, int length,
			long presentationTimeUs) {
		if (!running || decoder == null) return;
		try {
			int inputIndex = decoder.dequeueInputBuffer(5000);
			if (inputIndex >= 0) {
				ByteBuffer inputBuffer =
						decoder.getInputBuffer(inputIndex);
				if (inputBuffer != null) {
					inputBuffer.clear();
					inputBuffer.put(data, offset, length);
					decoder.queueInputBuffer(inputIndex, 0, length,
							presentationTimeUs, 0);
					decodeFailures = 0;
				}
			}
		} catch (Exception e) {
			decodeFailures++;
			if (decodeFailures >= 10) {
				running = false;
			}
		}
	}

	private void drainDecoder() {
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		while (running && decoder != null) {
			try {
				int outputIndex = decoder.dequeueOutputBuffer(
						info, 10000);
				if (outputIndex >= 0) {
					decoder.releaseOutputBuffer(outputIndex, true);
				}
			} catch (Exception e) {
				if (!running) break;
			}
		}
	}

	void stop() {
		running = false;
		if (decoder != null) {
			try {
				decoder.stop();
			} catch (Exception ignored) {}
			try {
				decoder.release();
			} catch (Exception ignored) {}
			decoder = null;
		}
	}

	boolean isRunning() {
		return running;
	}
}
