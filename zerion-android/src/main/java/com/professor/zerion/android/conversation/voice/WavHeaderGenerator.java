package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@NotNullByDefault
public class WavHeaderGenerator {

	private static final int SAMPLE_RATE = 8000;
	private static final int CHANNELS = 1;
	private static final int BITS_PER_SAMPLE = 16;

	public static byte[] addWavHeader(byte[] pcmData) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		int dataSize = pcmData.length;
		int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
		int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

		ByteBuffer buffer = ByteBuffer.allocate(44);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		buffer.put("RIFF".getBytes());
		buffer.putInt(36 + dataSize);
		buffer.put("WAVE".getBytes());
		buffer.put("fmt ".getBytes());
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) CHANNELS);
		buffer.putInt(SAMPLE_RATE);
		buffer.putInt(byteRate);
		buffer.putShort((short) blockAlign);
		buffer.putShort((short) BITS_PER_SAMPLE);
		buffer.put("data".getBytes());
		buffer.putInt(dataSize);

		baos.write(buffer.array());
		baos.write(pcmData);

		return baos.toByteArray();
	}
}
