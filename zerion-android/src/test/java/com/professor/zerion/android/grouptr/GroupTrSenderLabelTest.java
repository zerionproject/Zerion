package com.professor.zerion.android.grouptr;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GroupTrSenderLabelTest {

	private static final String CHOSEN = "%1$s (posting as \"%2$s\")";
	private static final String UNVERIFIED = "\"%1$s\" (not a name you know)";

	@Test
	public void theKnownNameLeadsAndAChosenNameIsMarked() {
		assertEquals("Mallory (posting as \"Alice\") · fp",
				GroupTrSenderLabel.label("Mallory", "Alice", "fp", CHOSEN,
						UNVERIFIED));
		assertEquals("Alice · fp", GroupTrSenderLabel.label("Alice",
				"Alice", "fp", CHOSEN, UNVERIFIED));
		assertEquals("Alice · fp", GroupTrSenderLabel.label("Alice", "",
				"fp", CHOSEN, UNVERIFIED));
	}

	@Test
	public void aSenderKnownByNoNameIsMarkedUnverified() {
		assertEquals("\"Alice\" (not a name you know) · fp",
				GroupTrSenderLabel.label(null, "Alice", "fp", CHOSEN,
						UNVERIFIED));
	}

	@Test
	public void theFingerprintCarriesSixtyFourBits() {
		byte[] a = new byte[32];
		byte[] b = new byte[32];
		Arrays.fill(b, (byte) 1);
		String fa = GroupTrSenderLabel.fingerprint(a);
		assertEquals(19, fa.length());
		assertTrue(fa.matches("[0-9a-f]{4}( [0-9a-f]{4}){3}"));
		assertNotEquals(fa, GroupTrSenderLabel.fingerprint(b));
	}
}
