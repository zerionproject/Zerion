package com.professor.zerion.android.settings;

import org.junit.Test;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OnionRotationRequestTest {

	private static final String NEXT =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";

	private static B4OnionRotation rotation(boolean starts,
			RotationPhase phase, @Nullable String next, boolean fails) {
		return new B4OnionRotation(null, null, null, null) {
			@Override
			public boolean forceRotate() throws DbException {
				if (fails) throw new DbException();
				return starts;
			}

			@Override
			public RotationPhase getPhase() {
				return phase;
			}

			@Override
			public String getAliceNextOnion() {
				return next;
			}
		};
	}

	private static String read(String path) throws IOException {
		return new String(Files.readAllBytes(new File(path).toPath()),
				StandardCharsets.UTF_8);
	}

	@Test
	public void aStartedRotationReportsTheNewAddress() {
		OnionRotationRequest.Result r = OnionRotationRequest.run(
				rotation(true, RotationPhase.ANNOUNCING, NEXT, false));
		assertEquals(OnionRotationRequest.Outcome.STARTED, r.outcome);
		assertEquals(NEXT, r.onion);
	}

	@Test
	public void aRotationAlreadyRunningIsNotReportedAsANewAddress() {
		OnionRotationRequest.Result r = OnionRotationRequest.run(
				rotation(false, RotationPhase.ANNOUNCING, NEXT, false));
		assertEquals(OnionRotationRequest.Outcome.ALREADY_RUNNING, r.outcome);
		assertNull(r.onion);
	}

	@Test
	public void aRotationThatCouldNotStartIsAFailure() {
		assertEquals(OnionRotationRequest.Outcome.FAILED,
				OnionRotationRequest.run(rotation(false, RotationPhase.IDLE,
						null, false)).outcome);
		assertEquals(OnionRotationRequest.Outcome.FAILED,
				OnionRotationRequest.run(rotation(true,
						RotationPhase.ANNOUNCING, null, false)).outcome);
		assertEquals(OnionRotationRequest.Outcome.FAILED,
				OnionRotationRequest.run(rotation(false,
						RotationPhase.ANNOUNCING, NEXT, true)).outcome);
	}

	@Test
	public void theScreenSaysWhenARotationIsAlreadyRunning()
			throws IOException {
		String fragment = read("src/main/java/com/professor/zerion/android/"
				+ "settings/ConnectionsFragment.java");
		assertTrue("the already running message is never shown",
				fragment.contains("R.string.pref_rotate_onion_already_running"));
		assertTrue(fragment.contains("OnionRotationRequest.run("));
		assertFalse(fragment.contains("getAliceNextOnion"));
	}

	@Test
	public void theRotationTextsPromiseNoRetirementWithoutEvidence()
			throws IOException {
		String english = read("src/main/res/values/strings.xml");
		for (String key : new String[] {"pref_rotate_onion_confirm_message",
				"pref_rotate_onion_success_message"}) {
			int at = english.indexOf("name=\"" + key + "\"");
			assertTrue(key, at >= 0);
			String line = english.substring(at, english.indexOf('\n', at));
			assertFalse(key + " promises a retirement after a fixed time",
					line.contains("90"));
		}
	}
}
