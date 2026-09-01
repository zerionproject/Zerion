package com.professor.zerion.android.settings;

import org.junit.Test;

import static com.professor.zerion.android.settings.BackgroundConnections.Mode.ALWAYS;
import static com.professor.zerion.android.settings.BackgroundConnections.Mode.PAUSED;
import static com.professor.zerion.android.settings.BackgroundConnections.Mode.WHILE_OPEN;
import static com.professor.zerion.android.settings.BackgroundConnections.allowStart;
import static com.professor.zerion.android.settings.BackgroundConnections.requireStop;
import static com.professor.zerion.android.settings.BackgroundConnections.shouldRun;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackgroundConnectionsTest {

	@Test
	public void signedOutNeverRuns() {
		for (BackgroundConnections.Mode m :
				BackgroundConnections.Mode.values()) {
			assertFalse(shouldRun(m, false, true));
			assertFalse(shouldRun(m, false, false));
			assertFalse(allowStart(m, false, true));
			assertFalse(requireStop(m, false, true));
		}
	}

	@Test
	public void alwaysRunsWhenSignedIn() {
		assertTrue(shouldRun(ALWAYS, true, true));
		assertTrue(shouldRun(ALWAYS, true, false));
		assertTrue(allowStart(ALWAYS, true, false));
		assertFalse(requireStop(ALWAYS, true, false));
	}

	@Test
	public void pausedNeverRunsWhenSignedIn() {
		assertFalse(shouldRun(PAUSED, true, true));
		assertFalse(shouldRun(PAUSED, true, false));
		assertFalse(allowStart(PAUSED, true, true));
		assertTrue("paused while signed in must require a stop",
				requireStop(PAUSED, true, true));
	}

	@Test
	public void whileOpenFollowsForegroundState() {
		assertTrue("running while foreground", shouldRun(WHILE_OPEN, true, true));
		assertFalse("stopped while background",
				shouldRun(WHILE_OPEN, true, false));
		assertTrue(allowStart(WHILE_OPEN, true, true));
		assertFalse(allowStart(WHILE_OPEN, true, false));
		assertTrue("background must stop it",
				requireStop(WHILE_OPEN, true, false));
		assertFalse("foreground must not stop it",
				requireStop(WHILE_OPEN, true, true));
	}

	@Test
	public void modeFromValueRoundTripsAndDefaults() {
		assertEquals(ALWAYS, BackgroundConnections.Mode.fromValue("always"));
		assertEquals(WHILE_OPEN,
				BackgroundConnections.Mode.fromValue("while_open"));
		assertEquals(PAUSED, BackgroundConnections.Mode.fromValue("paused"));
		assertEquals("unknown defaults to ALWAYS", ALWAYS,
				BackgroundConnections.Mode.fromValue("nonsense"));
		assertEquals("null defaults to ALWAYS", ALWAYS,
				BackgroundConnections.Mode.fromValue(null));
	}

	@Test
	public void defaultIsAlwaysConnected() {
		assertEquals(ALWAYS, BackgroundConnections.getMode(null));
	}
}
