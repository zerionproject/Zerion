package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
class VideoBandwidthAdapter {

	enum Quality {
		FULL(250_000, 15),
		REDUCED(150_000, 10),
		MINIMAL(80_000, 5),
		OFF(0, 0);

		final int bitRate;
		final int frameRate;

		Quality(int bitRate, int frameRate) {
			this.bitRate = bitRate;
			this.frameRate = frameRate;
		}
	}

	private Quality currentQuality = Quality.FULL;
	private long lastAdjustmentTime = 0;
	private static final long ADJUSTMENT_COOLDOWN_MS = 5000;

	private float smoothedLatency = 0;
	private float smoothedPacketLoss = 0;

	Quality getCurrentQuality() {
		return currentQuality;
	}

	Quality updateMetrics(float latencyMs, float packetLossPercent) {

		smoothedLatency = smoothedLatency * 0.7f + latencyMs * 0.3f;
		smoothedPacketLoss = smoothedPacketLoss * 0.7f +
				packetLossPercent * 0.3f;

		long now = System.currentTimeMillis();
		if (now - lastAdjustmentTime < ADJUSTMENT_COOLDOWN_MS) {
			return currentQuality;
		}

		Quality recommended;
		if (smoothedPacketLoss > 10 || smoothedLatency > 3000) {
			recommended = Quality.OFF;
		} else if (smoothedPacketLoss > 5 || smoothedLatency > 2000) {
			recommended = Quality.MINIMAL;
		} else if (smoothedPacketLoss > 2 || smoothedLatency > 1000) {
			recommended = Quality.REDUCED;
		} else {
			recommended = Quality.FULL;
		}

		if (recommended != currentQuality) {
			currentQuality = recommended;
			lastAdjustmentTime = now;
		}

		return currentQuality;
	}

	void reset() {
		currentQuality = Quality.FULL;
		smoothedLatency = 0;
		smoothedPacketLoss = 0;
		lastAdjustmentTime = 0;
	}
}
