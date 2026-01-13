package com.professor.zerion.android.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TorStatusMonitorTest {

	@Test
	public void testBandwidthUpdateContainsSafeValues() {
		TorStatusMonitor.BandwidthUpdate update = new TorStatusMonitor.BandwidthUpdate(
				100L, 50L, 1000L, 500L
		);

		assertEquals("Download speed should match", 100L, update.downloadSpeed);
		assertEquals("Upload speed should match", 50L, update.uploadSpeed);
		assertEquals("Total download should match", 1000L, update.totalDownload);
		assertEquals("Total upload should match", 500L, update.totalUpload);
	}

	@Test
	public void testBandwidthUpdateHandlesZeroValues() {
		TorStatusMonitor.BandwidthUpdate update = new TorStatusMonitor.BandwidthUpdate(
				0L, 0L, 0L, 0L
		);

		assertEquals("Download speed should be 0", 0L, update.downloadSpeed);
		assertEquals("Upload speed should be 0", 0L, update.uploadSpeed);
		assertEquals("Total download should be 0", 0L, update.totalDownload);
		assertEquals("Total upload should be 0", 0L, update.totalUpload);
	}

	@Test
	public void testBandwidthUpdateNeverNegative() {
		TorStatusMonitor.BandwidthUpdate update = new TorStatusMonitor.BandwidthUpdate(
				100L, 50L, 1000L, 500L
		);

		assertTrue("Download speed should never be negative", update.downloadSpeed >= 0);
		assertTrue("Upload speed should never be negative", update.uploadSpeed >= 0);
		assertTrue("Total download should never be negative", update.totalDownload >= 0);
		assertTrue("Total upload should never be negative", update.totalUpload >= 0);
	}

	@Test
	public void testTorStatisticsDefaultValues() {
		TorStatusMonitor.TorStatistics stats = new TorStatusMonitor.TorStatistics();

		assertEquals("Default bytesReceived should be 0", 0L, stats.bytesReceived);
		assertEquals("Default bytesSent should be 0", 0L, stats.bytesSent);
		assertEquals("Default circuitsBuilt should be 0", 0, stats.circuitsBuilt);
		assertEquals("Default circuitsFailed should be 0", 0, stats.circuitsFailed);
		assertEquals("Default uptimeSeconds should be 0", 0L, stats.uptimeSeconds);
		assertEquals("Default peakDownloadSpeed should be 0", 0L, stats.peakDownloadSpeed);
		assertEquals("Default peakUploadSpeed should be 0", 0L, stats.peakUploadSpeed);
		assertEquals("Default averageDownloadSpeed should be 0", 0L, stats.averageDownloadSpeed);
		assertEquals("Default averageUploadSpeed should be 0", 0L, stats.averageUploadSpeed);
		assertEquals("Default currentDownloadSpeed should be 0", 0L, stats.currentDownloadSpeed);
		assertEquals("Default currentUploadSpeed should be 0", 0L, stats.currentUploadSpeed);
	}

	@Test
	public void testTorStatusDataClass() {
		TorStatusMonitor.TorStatus status = new TorStatusMonitor.TorStatus(
				true, "Connected", 100
		);

		assertTrue("isConnected should be true", status.isConnected);
		assertEquals("statusMessage should match", "Connected", status.statusMessage);
		assertEquals("bootstrapProgress should be 100", 100, status.bootstrapProgress);
	}

	@Test
	public void testTorStatusDisconnected() {
		TorStatusMonitor.TorStatus status = new TorStatusMonitor.TorStatus(
				false, "Disconnected", 0
		);

		assertTrue("isConnected should be false", !status.isConnected);
		assertEquals("statusMessage should match", "Disconnected", status.statusMessage);
		assertEquals("bootstrapProgress should be 0", 0, status.bootstrapProgress);
	}

	@Test
	public void testTorCircuitDataClass() {
		java.util.List<TorStatusMonitor.TorNode> nodes = java.util.Arrays.asList(
				new TorStatusMonitor.TorNode("Guard", "US", "1.2.3.4"),
				new TorStatusMonitor.TorNode("Middle", "DE", "5.6.7.8"),
				new TorStatusMonitor.TorNode("Exit", "NL", "9.10.11.12")
		);

		TorStatusMonitor.TorCircuit circuit = new TorStatusMonitor.TorCircuit(
				1, "BUILT", nodes, System.currentTimeMillis()
		);

		assertEquals("Circuit ID should be 1", 1, circuit.id);
		assertEquals("Circuit status should be BUILT", "BUILT", circuit.status);
		assertNotNull("Nodes should not be null", circuit.nodes);
		assertEquals("Should have 3 nodes", 3, circuit.nodes.size());
	}

	@Test
	public void testTorNodeDataClass() {
		TorStatusMonitor.TorNode node = new TorStatusMonitor.TorNode(
				"Guard", "United States", "192.168.1.1"
		);

		assertEquals("Node type should match", "Guard", node.type);
		assertEquals("Node country should match", "United States", node.country);
		assertEquals("Node IP should match", "192.168.1.1", node.ip);
	}
}
