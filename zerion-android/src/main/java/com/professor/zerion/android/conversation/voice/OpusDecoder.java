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
	private long presentationTimeUs = 0;

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
			byte[] csd0 = buildOpusHead();
			byte[] csd1 = new byte[8];
			byte[] csd2 = new byte[8];
			format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
			format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
			format.setByteBuffer("csd-2", ByteBuffer.wrap(csd2));

			decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
			decoder.configure(format, null, null, 0);
			decoder.start();
			isInitialized = true;
		} catch (IOException e) {
			if (decoder != null) {
				try {
					decoder.release();
				} catch (Exception ignored) {
				}
				decoder = null;
			}
		}
	}

	private byte[] buildOpusHead() {
		byte[] head = new byte[19];
		head[0] = 'O'; head[1] = 'p'; head[2] = 'u'; head[3] = 's';
		head[4] = 'H'; head[5] = 'e'; head[6] = 'a'; head[7] = 'd';
		head[8] = 1;
		head[9] = (byte) channelCount;
		head[10] = 0; head[11] = 0;
		head[12] = (byte) (sampleRate & 0xFF);
		head[13] = (byte) ((sampleRate >> 8) & 0xFF);
		head[14] = (byte) ((sampleRate >> 16) & 0xFF);
		head[15] = (byte) ((sampleRate >> 24) & 0xFF);
		head[16] = 0; head[17] = 0;
		head[18] = 0;
		return head;
	}

	// Maximum decoded PCM frame size (20ms at 48kHz mono 16-bit = 1920 bytes)
	// Allow 4x margin for multi-frame decoding
	private static final int MAX_DECODED_FRAME_SIZE = 1920 * 4;

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
					decoder.queueInputBuffer(inputBufferIndex, 0, opusData.length,
							presentationTimeUs, 0);
					presentationTimeUs += 20000;
				}
			}
			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			byte[] result = null;
			int totalSize = 0;
			while (true) {
				int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000);
				if (outputBufferIndex >= 0) {
					ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);
					if (outputBuffer != null && bufferInfo.size > 0) {
						// Reject oversized decoded output
						if (totalSize + bufferInfo.size > MAX_DECODED_FRAME_SIZE) {
							decoder.releaseOutputBuffer(outputBufferIndex, false);
							break;
						}
						byte[] pcm = new byte[bufferInfo.size];
						outputBuffer.get(pcm);
						if (result == null) {
							result = pcm;
						} else {
							byte[] combined = new byte[result.length + pcm.length];
							System.arraycopy(result, 0, combined, 0, result.length);
							System.arraycopy(pcm, 0, combined, result.length, pcm.length);
							java.util.Arrays.fill(result, (byte) 0);
							result = combined;
						}
						totalSize += bufferInfo.size;
					}
					decoder.releaseOutputBuffer(outputBufferIndex, false);
				} else {
					break;
				}
			}
			if (result != null) return result;

		} catch (Exception e) {
		}

		return new byte[0];
	}

	public byte[] concealLostPacket(int frameSize) {
		if (!isInitialized || decoder == null) {
			return new byte[frameSize];
		}

		try {
			int inputBufferIndex = decoder.dequeueInputBuffer(5000);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
				if (inputBuffer != null) {
					inputBuffer.clear();
					decoder.queueInputBuffer(inputBufferIndex, 0, 0,
							presentationTimeUs, 0);
					presentationTimeUs += 20000;
				}
			}

			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			int outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000);

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

		return new byte[frameSize];
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
