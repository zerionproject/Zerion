package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Simple audio codec utilities for mu-law encoding/decoding.
 * Mu-law (G.711) provides 2:1 compression for voice audio.
 */
@NotNullByDefault
public class AudioCodec {

	// Mu-law decoding table (8-bit mu-law -> 16-bit linear)
	private static final short[] MULAW_DECODE_TABLE = new short[256];

	static {
		// Build decode table
		for (int i = 0; i < 256; i++) {
			MULAW_DECODE_TABLE[i] = muLawToLinearSample((byte) i);
		}
	}

	/**
	 * Convert mu-law encoded audio back to 16-bit PCM.
	 * @param muLawData mu-law encoded audio (8-bit samples)
	 * @return 16-bit PCM audio (little-endian)
	 */
	public static byte[] muLawToPcm(byte[] muLawData) {
		byte[] pcmData = new byte[muLawData.length * 2];

		for (int i = 0; i < muLawData.length; i++) {
			short sample = MULAW_DECODE_TABLE[muLawData[i] & 0xFF];
			// Write as little-endian
			pcmData[i * 2] = (byte) (sample & 0xFF);
			pcmData[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
		}

		return pcmData;
	}

	/**
	 * Convert 16-bit PCM to mu-law encoding.
	 * @param pcmData 16-bit PCM audio (little-endian)
	 * @return mu-law encoded audio (8-bit samples)
	 */
	public static byte[] pcmToMuLaw(byte[] pcmData) {
		int numSamples = pcmData.length / 2;
		byte[] muLawData = new byte[numSamples];

		for (int i = 0; i < numSamples; i++) {
			// Read 16-bit sample (little-endian)
			int sample = (pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8);
			muLawData[i] = linearToMuLaw((short) sample);
		}

		return muLawData;
	}

	/**
	 * Convert a single 16-bit linear sample to 8-bit mu-law.
	 */
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

	/**
	 * Convert a single 8-bit mu-law sample to 16-bit linear.
	 */
	private static short muLawToLinearSample(byte muLawByte) {
		// Invert all bits
		int muLaw = ~muLawByte & 0xFF;

		int sign = muLaw & 0x80;
		int exponent = (muLaw >> 4) & 0x07;
		int mantissa = muLaw & 0x0F;

		// Compute linear value
		int sample = ((mantissa << 3) + 0x84) << exponent;
		sample -= 0x84;

		return (short) (sign != 0 ? -sample : sample);
	}
}
