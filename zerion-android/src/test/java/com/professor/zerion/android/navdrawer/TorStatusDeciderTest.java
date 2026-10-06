package com.professor.zerion.android.navdrawer;

import org.junit.Test;
import org.zerionproject.core.api.plugin.Plugin;

import static com.professor.zerion.android.navdrawer.TorStatusDecider.Status;
import static org.junit.Assert.assertEquals;

public class TorStatusDeciderTest {

	private static final Plugin.State ACTIVE = Plugin.State.ACTIVE;

	@Test
	public void offlineModeWins() {
		assertEquals(Status.OFFLINE_MODE,
				TorStatusDecider.decide(true, ACTIVE, true, true, false, 100));
	}

	@Test
	public void disabledWhenNullOrDisabled() {
		assertEquals(Status.DISABLED,
				TorStatusDecider.decide(false, null, true, false, false, 0));
		assertEquals(Status.DISABLED, TorStatusDecider.decide(false,
				Plugin.State.DISABLED, true, false, false, 0));
	}

	@Test
	public void activeOnlineAndPublishedIsConnected() {
		assertEquals(Status.CONNECTED,
				TorStatusDecider.decide(false, ACTIVE, true, true, false, 100));
	}

	@Test
	public void activeOnlineNotPublishedIsPublishing() {
		assertEquals(Status.PUBLISHING,
				TorStatusDecider.decide(false, ACTIVE, true, false, false, 100));
	}

	@Test
	public void clockSkewIsSurfacedAndNeverShownAsConnected() {
		assertEquals(Status.CLOCK_SKEW,
				TorStatusDecider.decide(false, ACTIVE, true, false, true, 100));
		assertEquals("a skew hides a stale published flag rather than"
						+ " claiming connected", Status.CLOCK_SKEW,
				TorStatusDecider.decide(false, ACTIVE, true, true, true, 100));
	}

	@Test
	public void offlineWhileActiveFallsBackToBootstrapOrConnecting() {
		assertEquals(Status.BOOTSTRAPPING,
				TorStatusDecider.decide(false, ACTIVE, false, false, false, 40));
		assertEquals(Status.CONNECTING,
				TorStatusDecider.decide(false, ACTIVE, false, false, false, 0));
	}

	@Test
	public void startingWithBootstrapProgressIsBootstrapping() {
		assertEquals(Status.BOOTSTRAPPING, TorStatusDecider.decide(false,
				Plugin.State.STARTING_STOPPING, true, false, false, 55));
	}

	@Test
	public void startingWithoutProgressIsConnecting() {
		assertEquals(Status.CONNECTING, TorStatusDecider.decide(false,
				Plugin.State.STARTING_STOPPING, true, false, false, 0));
	}

	@Test
	public void aSkewIsShownWhileTorIsStillBootstrapping() {
		assertEquals(Status.CLOCK_SKEW, TorStatusDecider.decide(false,
				Plugin.State.STARTING_STOPPING, true, false, true, 40));
		assertEquals(Status.CLOCK_SKEW,
				TorStatusDecider.decide(false, ACTIVE, false, false, true, 0));
	}

	@Test
	public void offlineModeAndDisabledStillTakePrecedenceOverASkew() {
		assertEquals(Status.OFFLINE_MODE,
				TorStatusDecider.decide(true, ACTIVE, true, false, true, 100));
		assertEquals(Status.DISABLED, TorStatusDecider.decide(false,
				Plugin.State.DISABLED, true, false, true, 0));
	}
}
