package com.professor.zerion.android.vault.crypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class Argon2NativeCopyTest {

	@Test
	public void theNativeCallGetsAPrivateCopy() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/vault/crypto/"
						+ "Argon2.java")), StandardCharsets.UTF_8);
		assertTrue(src.contains("byte[] nativeCopy = passwordBytes.clone();"));
		assertTrue(src.contains("NativeArgon2.deriveOrNull(nativeCopy, salt,"));
		assertTrue(src.contains(
				"deriveKeyBouncyCastle(passwordBytes, salt, params)"));
		assertTrue(src.contains("Arrays.fill(nativeCopy, (byte) 0);"));
	}
}
