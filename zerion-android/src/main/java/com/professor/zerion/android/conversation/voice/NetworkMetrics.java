package com.professor.zerion.android.conversation.voice;

import java.util.ArrayDeque;
import java.util.Queue;

public class NetworkMetrics {

	private long totalPacketsSent = 0;
	private long totalPacketsReceived = 0;
	private long expectedPackets = 0;
	private long corruptedPackets = 0;

	private static final int LATENCY_WINDOW_SIZE = 10;
	private final Queue<Long> latencySamples = new ArrayDeque<>(LATENCY_WINDOW_SIZE);
	private long currentLatencyMs = 0;

	private long previousLatencyMs = 0;
	private long totalJitter = 0;
	private long jitterSamples = 0;
	private double ewmaJitterMs = 0;
	private static final double EWMA_ALPHA = 0.1;

	private long underrunCount = 0;
	private long writeErrors = 0;

	public static final int SIGNAL_EXCELLENT = 5;
	public static final int SIGNAL_GOOD = 4;
	public static final int SIGNAL_FAIR = 3;
	public static final int SIGNAL_POOR = 2;
	public static final int SIGNAL_VERY_POOR = 1;

	private String codecName = "PCM";
	private int bitrateKbps = 256;

	public synchronized void recordPacketSent() {
		totalPacketsSent++;
	}

	public synchronized void recordPacketReceived(long sequenceNumber) {
		totalPacketsReceived++;

		if (sequenceNumber > expectedPackets) {
			expectedPackets = sequenceNumber;
		}
	}

	public synchronized void recordCorruptedPacket() {
		corruptedPackets++;
	}

	public synchronized void recordLatency(long latencyMs) {
		latencySamples.add(latencyMs);
		if (latencySamples.size() > LATENCY_WINDOW_SIZE) {
			latencySamples.poll();
		}

		long sum = 0;
		for (long sample : latencySamples) {
			sum += sample;
		}
		currentLatencyMs = sum / latencySamples.size();

		if (previousLatencyMs > 0) {
			long jitter = Math.abs(latencyMs - previousLatencyMs);
			totalJitter += jitter;
			jitterSamples++;
			ewmaJitterMs = ewmaJitterMs == 0 ? jitter :
					(EWMA_ALPHA * jitter) + ((1.0 - EWMA_ALPHA) * ewmaJitterMs);
		}
		previousLatencyMs = latencyMs;
	}

	public synchronized void setCodecInfo(String codecName, int bitrateKbps) {
		this.codecName = codecName;
		this.bitrateKbps = bitrateKbps;
	}

	public synchronized long getLatencyMs() {
		return currentLatencyMs;
	}

	public synchronized long getAverageJitter() {
		return jitterSamples > 0 ? totalJitter / jitterSamples : 0;
	}

	public synchronized long getEwmaJitter() {
		return (long) ewmaJitterMs;
	}

	public synchronized void recordUnderrun() {
		underrunCount++;
	}

	public synchronized void recordWriteError() {
		writeErrors++;
	}

	public synchronized long getUnderrunCount() {
		return underrunCount;
	}

	public synchronized long getWriteErrors() {
		return writeErrors;
	}

	public synchronized double getPacketLossPercentage() {
		if (expectedPackets == 0) {
			return 0.0;
		}

		long lostPackets = expectedPackets - totalPacketsReceived;
		if (lostPackets < 0) lostPackets = 0;

		return (lostPackets * 100.0) / expectedPackets;
	}

	public synchronized double getCorruptionPercentage() {
		if (totalPacketsReceived == 0) {
			return 0.0;
		}
		return (corruptedPackets * 100.0) / totalPacketsReceived;
	}

	public synchronized int getSignalQuality() {
		double packetLoss = getPacketLossPercentage();
		long latency = currentLatencyMs;

		if (latency < 100 && packetLoss < 1.0) {
			return SIGNAL_EXCELLENT;
		}
		else if (latency < 150 && packetLoss < 2.0) {
			return SIGNAL_GOOD;
		}
		else if (latency < 250 && packetLoss < 5.0) {
			return SIGNAL_FAIR;
		}
		else if (latency < 400 && packetLoss < 10.0) {
			return SIGNAL_POOR;
		}
		else {
			return SIGNAL_VERY_POOR;
		}
	}

	public synchronized String getCodecName() {
		return codecName;
	}

	public synchronized int getBitrateKbps() {
		return bitrateKbps;
	}

	public synchronized String getCodecBitrateDisplay() {
		return String.format("%s %dkbps", codecName, bitrateKbps);
	}

	public synchronized long getTotalPacketsSent() {
		return totalPacketsSent;
	}

	public synchronized long getTotalPacketsReceived() {
		return totalPacketsReceived;
	}

	public synchronized void reset() {
		totalPacketsSent = 0;
		totalPacketsReceived = 0;
		expectedPackets = 0;
		corruptedPackets = 0;
		latencySamples.clear();
		currentLatencyMs = 0;
		previousLatencyMs = 0;
		totalJitter = 0;
		jitterSamples = 0;
		ewmaJitterMs = 0;
		underrunCount = 0;
		writeErrors = 0;
	}

	public synchronized String getSummary() {
		return String.format("Network Quality Summary:\n" +
						"  Latency: %dms (jitter: %dms)\n" +
						"  Packet Loss: %.2f%%\n" +
						"  Corruption: %.2f%%\n" +
						"  Signal Quality: %d/5 bars\n" +
						"  Codec: %s\n" +
						"  Packets: %d sent, %d received",
				currentLatencyMs, getAverageJitter(),
				getPacketLossPercentage(), getCorruptionPercentage(),
				getSignalQuality(), getCodecBitrateDisplay(),
				totalPacketsSent, totalPacketsReceived);
	}
}
