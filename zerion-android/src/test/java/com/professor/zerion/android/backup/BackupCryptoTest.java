package com.professor.zerion.android.backup;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.NOT_A_BACKUP;
import static com.professor.zerion.android.backup.BackupException.Reason.WRONG_PASSPHRASE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupCryptoTest {

	@Test
	public void sealOpenRoundTripAndFailureModes() throws BackupException {
		BackupCrypto crypto = new BackupCrypto();
		byte[] dbKey = new byte[32];
		for (int i = 0; i < 32; i++) dbKey[i] = (byte) (i + 1);
		byte[] dbFile = "pretend sqlcipher database bytes"
				.getBytes(StandardCharsets.UTF_8);
		BackupBundle bundle =
				new BackupBundle("Alice", dbKey.clone(), dbFile.clone(), null);
		byte[] sealed = crypto.seal(bundle.toBytes(),
				"correct passphrase".toCharArray(), (byte) 0);

		BackupCrypto.Opened opened =
				crypto.open(sealed, "correct passphrase".toCharArray());
		assertFalse(opened.isVaultIncluded());
		BackupBundle restored = BackupBundle.fromBytes(opened.bundle);
		assertEquals("Alice", restored.displayName);
		assertArrayEquals(dbKey, restored.dbKey);
		assertArrayEquals(dbFile, restored.dbFile);
		assertNull(restored.vault);

		try {
			crypto.open(sealed, "wrong passphrase".toCharArray());
			fail("expected WRONG_PASSPHRASE");
		} catch (BackupException e) {
			assertEquals(WRONG_PASSPHRASE, e.reason);
		}

		byte[] tampered = sealed.clone();
		tampered[tampered.length - 1] ^= (byte) 0xFF;
		try {
			crypto.open(tampered, "correct passphrase".toCharArray());
			fail("expected CORRUPT");
		} catch (BackupException e) {
			assertEquals(CORRUPT, e.reason);
		}
	}

	@Test
	public void rejectsNonBackupBytes() {
		BackupCrypto crypto = new BackupCrypto();
		try {
			crypto.open("this is not a Zerion backup file".getBytes(
					StandardCharsets.UTF_8), "x".toCharArray());
			fail("expected NOT_A_BACKUP");
		} catch (BackupException e) {
			assertEquals(NOT_A_BACKUP, e.reason);
		}
	}

	@Test
	public void bundleRoundTripsWithVault() throws BackupException {
		byte[] dbKey = new byte[32];
		Arrays.fill(dbKey, (byte) 5);
		byte[] dbFile = new byte[2048];
		Arrays.fill(dbFile, (byte) 7);
		byte[] vault = new byte[123];
		Arrays.fill(vault, (byte) 9);
		BackupBundle b = new BackupBundle("Bob 😀", dbKey, dbFile, vault);
		BackupBundle r = BackupBundle.fromBytes(b.toBytes());
		assertEquals("Bob 😀", r.displayName);
		assertArrayEquals(dbKey, r.dbKey);
		assertArrayEquals(dbFile, r.dbFile);
		assertArrayEquals(vault, r.vault);
	}

	@Test
	public void bundleWithoutVaultIsNull() throws BackupException {
		BackupBundle b = new BackupBundle("Carol", new byte[32], new byte[10],
				null);
		BackupBundle r = BackupBundle.fromBytes(b.toBytes());
		assertNull(r.vault);
		assertTrue(r.displayName.equals("Carol"));
	}
}
