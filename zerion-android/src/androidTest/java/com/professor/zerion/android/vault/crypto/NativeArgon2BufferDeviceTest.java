package com.professor.zerion.android.vault.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class NativeArgon2BufferDeviceTest {

	@Test
	public void aFailedNativeCallIsObservedAndReported() {
		assertTrue("native library loaded", NativeArgon2.isAvailable());
		byte[] pwd = "zt-buffer-probe".getBytes(StandardCharsets.UTF_8);
		byte[] original = pwd.clone();
		byte[] shortSalt = new byte[4];
		assertNull(NativeArgon2.deriveOrNull(pwd, shortSalt, 8, 1, 1, 32));
		boolean wiped = Arrays.equals(pwd, new byte[pwd.length]);
		android.os.Bundle status = new android.os.Bundle();
		status.putString("zt-argon2-probe", "callerArrayWipedOnFailure="
				+ wiped + " unchanged=" + Arrays.equals(pwd, original));
		androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
				.sendStatus(0, status);
	}

	@Test
	public void derivedKeysMatchTheReferenceForTheRealPassword() {
		char[] password = "correct horse".toCharArray();
		byte[] salt = new byte[16];
		Arrays.fill(salt, (byte) 7);
		Argon2.Argon2Params params =
				new Argon2.Argon2Params(8 * 1024, 1, 1, 32);
		byte[] viaArgon2 = new Argon2().deriveKey(password.clone(), salt,
				params);
		byte[] reference = Argon2.deriveKeyBouncyCastle(
				"correct horse".getBytes(StandardCharsets.UTF_8), salt, params);
		assertArrayEquals(reference, viaArgon2);
	}
}
