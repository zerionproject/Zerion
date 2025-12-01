package com.professor.zerion.android.conversation.voice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

@NotNullByDefault
public class OpusEncoder {

	private static final byte[] OPUS_HEAD_MAGIC = "OpusHead".getBytes();
	private static final byte[] OPUS_TAGS_MAGIC = "OpusTags".getBytes();
	private static final int OPUS_VERSION = 1;

	private final int sampleRate;
	private final int channelCount;
	private final int bitrate;

	private MediaCodec encoder;
	private boolean isInitialized = false;

	public OpusEncoder(int sampleRate, int channelCount, int bitrate) {
		this.sampleRate = sampleRate;
		this.channelCount = channelCount;
		this.bitrate = bitrate;
		initializeEncoder();
	}

	private void initializeEncoder() {
		try {
			MediaFormat format = MediaFormat.createAudioFormat(
					MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channelCount);

			format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
			format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount);
			format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
			format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
			format.setInteger("opus-frame-size", (sampleRate / 1000) * 20);
			format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);

			encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
			encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			encoder.start();
			isInitialized = true;
		} catch (IOException e) {
		}
	}

	public void writeHeader(OutputStream out) throws IOException {
		ByteBuffer header = ByteBuffer.allocate(47);

		header.put("OggS".getBytes());
		header.put((byte) 0);
		header.put((byte) 2);
		header.putLong(0);
		header.putInt(0);
		header.putInt(0);
		header.putInt(0);
		header.put((byte) 1);

		header.put((byte) 19);
		header.put(OPUS_HEAD_MAGIC);
		header.put((byte) OPUS_VERSION);
		header.put((byte) channelCount);
		header.putShort((short) 0);
		header.putInt(sampleRate);
		header.putShort((short) 0);
		header.put((byte) 0);

		out.write(header.array());
		writeCommentHeader(out);
	}

	private void writeCommentHeader(OutputStream out) throws IOException {
		ByteBuffer comments = ByteBuffer.allocate(44);

		comments.put("OggS".getBytes());
		comments.put((byte) 0);
		comments.put((byte) 0);
		comments.putLong(0);
		comments.putInt(0);
		comments.putInt(1);
		comments.putInt(0);
		comments.put((byte) 1);

		comments.put((byte) 16);
		comments.put(OPUS_TAGS_MAGIC);
		comments.putInt(0);
		comments.putInt(0);

		out.write(comments.array());
	}

	public byte[] encode(ByteBuffer pcmData, int sampleCount) {
		if (!isInitialized || encoder == null) {
			return new byte[0];
		}

		pcmData.rewind();

		if (sampleCount <= 0 || !pcmData.hasRemaining()) {
			return new byte[0];
		}

		try {
			int inputBufferIndex = encoder.dequeueInputBuffer(10000);
			if (inputBufferIndex >= 0) {
				ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferIndex);
				if (inputBuffer != null) {
					inputBuffer.clear();
					int inputSize = pcmData.remaining();
					inputBuffer.put(pcmData);
					encoder.queueInputBuffer(inputBufferIndex, 0, inputSize, 0, 0);
				}
			}

			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);

			if (outputBufferIndex >= 0) {
				ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferIndex);
				if (outputBuffer != null && bufferInfo.size > 0) {
					byte[] encoded = new byte[bufferInfo.size];
					outputBuffer.get(encoded);
					encoder.releaseOutputBuffer(outputBufferIndex, false);
					return encoded;
				}
				encoder.releaseOutputBuffer(outputBufferIndex, false);
			}

		} catch (Exception e) {
		}

		return new byte[0];
	}

	public void finalizeStream(OutputStream out) throws IOException {
		ByteBuffer eos = ByteBuffer.allocate(4);
		eos.put("OggS".getBytes());
		out.write(eos.array());
	}

	public void release() {
		if (encoder != null) {
			try {
				encoder.stop();
				encoder.release();
			} catch (Exception e) {
			}
			encoder = null;
		}
		isInitialized = false;
	}
}
