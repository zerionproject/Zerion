package com.professor.zerion.android.conversation.voice;

import android.media.MediaCodec;
import android.media.MediaFormat;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

@NotNullByDefault
public class OpusDecoder {

	private final int sampleRate;
	private final int channelCount;

	private MediaCodec decoder;
	private boolean isInitialized = false;

	public OpusDecoder(int sampleRate, int channelCount) {
		this.sampleRate = sampleRate;
		this.channelCount = channelCount;
		initializeDecoder();
	}

	private void initializeDecoder() {
		try {
			MediaFormat format = MediaFormat.createAudioFormat(
					MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount);

			format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
			format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);
			format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
			format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

			decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
			decoder.configure(format, null, null, 0);
			decoder.start();
			isInitialized = true;
		} catch (IOException e) {
		}
	}

	public byte[] decode(byte[] opusData) {
		if (!isInitialized || decoder == null || opusData == null || opusData.length == 0) {
			return new byte[0];
		}

		try {
			int inputBufferIndex = decoder.dequeueInputBuffer(10000);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
				if (inputBuffer != null) {
					inputBuffer.clear();
					inputBuffer.put(opusData);
					decoder.queueInputBuffer(inputBufferIndex, 0, opusData.length, 0, 0);
				}
			}

			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);

			if (outputBufferIndex >= 0) {
				ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);
				if (outputBuffer != null && bufferInfo.size > 0) {
					byte[] pcmData = new byte[bufferInfo.size];
					outputBuffer.get(pcmData);
					decoder.releaseOutputBuffer(outputBufferIndex, false);
					return pcmData;
				}
				decoder.releaseOutputBuffer(outputBufferIndex, false);
			}

		} catch (Exception e) {
		}

		return new byte[0];
	}

	public byte[] concealLostPacket(int frameSize) {
		byte[] silence = new byte[frameSize];
		return silence;
	}

	public void release() {
		if (decoder != null) {
			try {
				decoder.stop();
				decoder.release();
			} catch (Exception e) {
			}
			decoder = null;
		}
		isInitialized = false;
	}

	public boolean isInitialized() {
		return isInitialized;
	}
}
