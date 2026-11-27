package com.professor.zerion.android.backup;

import org.junit.Test;
import org.zerionproject.core.api.crypto.PasswordStrengthEstimator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupPassphraseTest {

	private static final PasswordStrengthEstimator SCORED_BY_LENGTH =
			p -> Math.min(1f, p.length / 16f);
	private static final PasswordStrengthEstimator ALWAYS_STRONG = p -> 1f;

	@Test
	public void shortPassphrasesAreRefused() {
		assertFalse(BackupPassphrase.longEnough(new char[0]));
		assertFalse(BackupPassphrase.longEnough("12345678".toCharArray()));
		assertFalse(BackupPassphrase.longEnough("12345678901".toCharArray()));
		assertTrue(BackupPassphrase.longEnough("123456789012".toCharArray()));
	}

	@Test
	public void floorIsTwelve() {
		assertEquals(12, BackupPassphrase.MIN_LENGTH);
	}

	@Test
	public void repeatedOrSingleClassPassphrasesAreRefused() {
		for (String weak : new String[]{"aaaaaaaaaaaa", "123456789012",
				"abcdefghijkl", "ABCDEFGHIJKL", "abababababab1",
				"aaaaaaa1111111"}) {
			assertFalse(weak, BackupPassphrase.wellFormed(weak.toCharArray()));
			assertFalse(weak, BackupPassphrase.acceptable(ALWAYS_STRONG,
					weak.toCharArray()));
		}
		for (String good : new String[]{"abcdefgh1234", "correct horse battery",
				"Tr0ub4dor&3xyz"}) {
			assertTrue(good, BackupPassphrase.wellFormed(good.toCharArray()));
		}
	}

	@Test
	public void acceptableNeedsTheFloorVarietyAndTheUpperStrengthBands() {
		assertFalse(BackupPassphrase.acceptable(ALWAYS_STRONG,
				"abcdefgh123".toCharArray()));
		assertFalse("a long passphrase in the lower bands is refused",
				BackupPassphrase.acceptable(p -> 0.74f,
						"abcdefgh12345678xyz".toCharArray()));
		assertTrue(BackupPassphrase.acceptable(
				p -> PasswordStrengthEstimator.QUITE_STRONG,
				"abcdefgh1234".toCharArray()));
		assertTrue(BackupPassphrase.acceptable(SCORED_BY_LENGTH,
				"abcdefgh1234".toCharArray()));
	}

	@Test
	public void strengthIsMeasuredOnTheNormalForm() {
		char[] seen = new char[1];
		BackupPassphrase.strength(p -> {
			seen[0] = p[0];
			return 0f;
		}, "Åbcdefghijkl".toCharArray());
		assertEquals('Å', seen[0]);
	}
}
