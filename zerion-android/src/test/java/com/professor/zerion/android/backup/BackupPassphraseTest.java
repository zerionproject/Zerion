package com.professor.zerion.android.backup;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A2-AND-09: the backup passphrase follows the profile password minimum. */
public class BackupPassphraseTest {

	@Test
	public void shortPassphrasesAreRefused() {
		assertFalse(BackupPassphrase.longEnough(new char[0]));
		assertFalse(BackupPassphrase.longEnough("1234567".toCharArray()));
		assertTrue(BackupPassphrase.longEnough("12345678".toCharArray()));
	}
}
