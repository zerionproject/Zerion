package org.zerionproject.transport;

import org.zerionproject.sync.ZppConnectionRunnerImpl;
import org.zerionproject.sync.ZppPacingPolicy;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class ZtpSessionTimeoutTest {

	@Test
	public void aSilentSessionIsClosedWithinTwentySeconds() {
		assertTrue(ZtpTorTransport.SOCKET_TIMEOUT_MS <= 20_000);
	}

	@Test
	public void aSessionWithoutCoverTrafficKeepsAnOlderPeerAlive() {
		long period = ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_LEGACY_MS;
		assertTrue(period + period / 2 < ZtpTorTransport.SOCKET_TIMEOUT_MS);
	}

	@Test
	public void anIdleSessionSendsSeveralFramesBeforeItTimesOut() {
		long idle = new ZppPacingPolicy().idleIntervalMs();
		long longestGap = idle + idle / 3;
		assertTrue(ZtpTorTransport.SOCKET_TIMEOUT_MS >= 3 * longestGap);
	}
}
