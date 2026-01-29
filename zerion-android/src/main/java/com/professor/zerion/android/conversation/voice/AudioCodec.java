package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public class AudioCodec {
	private static final short[] MULAW_DECODE_TABLE = new short[256];

	static {
		for (int i = 0; i < 256; i++) {
			MULAW_DECODE_TABLE[i] = muLawToLinearSample((byte) i);
		}
	}

	
	public static byte[] muLawToPcm(byte[] muLawData) {
		byte[] pcmData = new byte[muLawData.length * 2];

		for (int i = 0; i < muLawData.length; i++) {
			short sample = MULAW_DECODE_TABLE[muLawData[i] & 0xFF];
			pcmData[i * 2] = (byte) (sample & 0xFF);
			pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
		}

		return pcmData;
	}

	
	public static byte[] pcmToMuLaw(byte[] pcmData) {
		int numSamples = pcmData.length / 2;
		byte[] muLawData = new byte[numSamples];

		for (int i = 0; i < numSamples; i++) {
			int sample = (pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8);
			muLawData[i] = linearToMuLaw((short) sample);
		}

		return muLawData;
	}

	
	private static byte linearToMuLaw(short sample) {
		final int MULAW_MAX = 0x1FFF;
		final int MULAW_BIAS = 33;

		int sign = (sample >> 8) & 0x80;
		if (sign != 0) sample = (short) -sample;
		if (sample > MULAW_MAX) sample = MULAW_MAX;

		sample = (short) (sample + MULAW_BIAS);
		int exponent = 7;
		for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {}

		int mantissa = (sample >> (exponent + 3)) & 0x0F;
		return (byte) ~(sign | (exponent << 4) | mantissa);
	}

	
	private static short muLawToLinearSample(byte muLawByte) {
		int muLaw = ~muLawByte & 0xFF;

		int sign = muLaw & 0x80;
		int exponent = (muLaw >> 4) & 0x07;
		int mantissa = muLaw & 0x0F;
		int sample = ((mantissa << 3) + 0x84) << exponent;
		sample -= 0x84;

		return (short) (sign != 0 ? -sample : sample);
	}
}
