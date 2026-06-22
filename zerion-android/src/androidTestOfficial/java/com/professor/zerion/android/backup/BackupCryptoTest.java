package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class BackupCryptoTest {

	private final BackupCrypto backupCrypto = new BackupCrypto();
	private final Argon2 argon2 = new Argon2();
	private final VaultCrypto crypto = new VaultCrypto();
	private final byte[] payload =
			"this is the secret account bundle".getBytes();

	private Argon2.Argon2Params params() {
		return Argon2.Argon2Params.getLowMemory();
	}

	@Test
	public void v2RoundTrip() throws Exception {
		byte[] file = backupCrypto.seal(payload.clone(),
				"correct horse".toCharArray(), (byte) 0, params());
		assertEquals("magic", 'Z', file[0]);
		assertEquals("writes version 2", 2, file[4]);
		BackupCrypto.Opened opened =
				backupCrypto.open(file, "correct horse".toCharArray());
		assertArrayEquals(payload, opened.bundle);
	}

	@Test
	public void wrongPassphraseRejected() {
		byte[] file = backupCrypto.seal(payload.clone(),
				"right".toCharArray(), (byte) 0, params());
		try {
			backupCrypto.open(file, "wrong".toCharArray());
			fail("wrong passphrase accepted");
		} catch (BackupException e) {
			assertEquals(BackupException.Reason.WRONG_PASSPHRASE, e.reason);
		}
	}

	@Test
	public void tamperedSaltRejected() {
		byte[] file = backupCrypto.seal(payload.clone(),
				"pw".toCharArray(), (byte) 0, params());
		file[10] ^= 0x01;
		try {
			backupCrypto.open(file, "pw".toCharArray());
			fail("tampered salt accepted");
		} catch (BackupException e) {
			assertNotEquals(null, e.reason);
		}
	}

	@Test
	public void tamperedCiphertextRejected() {
		byte[] file = backupCrypto.seal(payload.clone(),
				"pw".toCharArray(), (byte) 0, params());
		file[file.length - 1] ^= 0x01;
		try {
			backupCrypto.open(file, "pw".toCharArray());
			fail("tampered ciphertext accepted");
		} catch (BackupException e) {
			assertEquals(BackupException.Reason.CORRUPT, e.reason);
		}
	}

	@Test
	public void legacyV1StillOpens() throws Exception {
		byte[] file = sealLegacyV1(payload.clone(), "legacy".toCharArray(),
				(byte) 0, params());
		assertEquals("crafted version 1", 1, file[4]);
		BackupCrypto.Opened opened =
				backupCrypto.open(file, "legacy".toCharArray());
		assertArrayEquals(payload, opened.bundle);
	}

	private byte[] sealLegacyV1(byte[] bundle, char[] passphrase, byte flags,
			Argon2.Argon2Params params) {
		byte[] salt = argon2.generateSalt();
		byte[] master = argon2.deriveKey(passphrase, salt, params);
		byte[] mac = crypto.computePasswordVerificationMac(master);
		byte[] sealed = crypto.encrypt(bundle, master, aadV1(flags)).toBytes();
		byte[] paramBytes = params.toBytes();
		ByteBuffer buf = ByteBuffer.allocate(4 + 2 + salt.length
				+ paramBytes.length + mac.length + 4 + sealed.length);
		buf.put(new byte[] {'Z', 'B', 'K', '1'});
		buf.put((byte) 1);
		buf.put(flags);
		buf.put(salt);
		buf.put(paramBytes);
		buf.put(mac);
		buf.putInt(sealed.length);
		buf.put(sealed);
		return buf.array();
	}

	private byte[] aadV1(byte flags) {
		byte[] base = "Zerion-Account-Backup-ZBK1"
				.getBytes(StandardCharsets.UTF_8);
		byte[] a = new byte[base.length + 2];
		System.arraycopy(base, 0, a, 0, base.length);
		a[base.length] = 1;
		a[base.length + 1] = flags;
		return a;
	}
}
