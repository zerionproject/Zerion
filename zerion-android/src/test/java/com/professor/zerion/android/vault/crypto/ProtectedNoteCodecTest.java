package com.professor.zerion.android.vault.crypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ProtectedNoteCodecTest {

	private static byte[] v1(String body, String password, byte firstSalt)
			throws Exception {
		byte[] salt = new byte[16];
		new SecureRandom().nextBytes(salt);
		salt[0] = firstSalt;
		byte[] iv = new byte[12];
		new SecureRandom().nextBytes(iv);
		byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(new PBEKeySpec(password.toCharArray(), salt,
						200000, 256)).getEncoded();
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(128, iv));
		byte[] ct = c.doFinal(body.getBytes(StandardCharsets.UTF_8));
		byte[] blob = new byte[28 + ct.length];
		System.arraycopy(salt, 0, blob, 0, 16);
		System.arraycopy(iv, 0, blob, 16, 12);
		System.arraycopy(ct, 0, blob, 28, ct.length);
		return blob;
	}

	@Test
	public void sealedNotesOpenWithTheirPasswordOnly() throws Exception {
		byte[] blob = ProtectedNoteCodec.seal("note body",
				"pw".toCharArray());
		assertEquals(0x02, blob[0]);
		assertEquals(1 + 32 + 12 + 9 + 16, blob.length);
		assertArrayEquals("note body".getBytes(StandardCharsets.UTF_8),
				ProtectedNoteCodec.open(blob, "pw".toCharArray()));
		assertNull(ProtectedNoteCodec.open(blob, "PW".toCharArray()));
	}

	@Test
	public void versionOneBlobsStillOpen() throws Exception {
		byte[] blob = v1("old body", "old", (byte) 0x31);
		assertArrayEquals("old body".getBytes(StandardCharsets.UTF_8),
				ProtectedNoteCodec.open(blob, "old".toCharArray()));
		assertNull(ProtectedNoteCodec.open(blob, "new".toCharArray()));
	}

	@Test
	public void tooShortBlobsOpenToNothing() throws Exception {
		assertNull(ProtectedNoteCodec.open(new byte[28], "x".toCharArray()));
		assertNull(ProtectedNoteCodec.open(new byte[0], "x".toCharArray()));
	}

	@Test
	public void theTitlePrefixIsTheLockSymbol() {
		assertEquals("🔒 ", ProtectedNoteCodec.TITLE_PREFIX);
	}
}
