package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Adaptive video quality controller for Tor bandwidth constraints.
 * Monitors network conditions and adjusts video parameters.
 */
@NotNullByDefault
class VideoBandwidthAdapter {

	enum Quality {
		FULL(250_000, 15),     // 250 kbps, 15 fps
		REDUCED(150_000, 10),  // 150 kbps, 10 fps
		MINIMAL(80_000, 5),    // 80 kbps, 5 fps
		OFF(0, 0);             // Video disabled

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

	// Smoothed metrics
	private float smoothedLatency = 0;
	private float smoothedPacketLoss = 0;

	Quality getCurrentQuality() {
		return currentQuality;
	}

	/**
	 * Update with current network metrics and return recommended quality.
	 */
	Quality updateMetrics(float latencyMs, float packetLossPercent) {
		// Exponential moving average
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
