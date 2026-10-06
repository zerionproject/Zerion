package com.professor.zerion.android.vault;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class VaultManagerThrottleSourceTest {

	private static final String SRC =
			"src/main/java/com/professor/zerion/android/vault/VaultManager.java";

	@Test
	public void verifyPasswordIsThrottledLikeUnlock() throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(SRC)),
				StandardCharsets.UTF_8);
		int start = s.indexOf("private synchronized boolean verifyPassword(");
		int end = s.indexOf("private boolean verifyPasswordUnthrottled(", start);
		assertTrue(start > 0 && end > start);
		String body = s.substring(start, end);
		assertTrue(body.contains("unlockThrottle.remainingLockoutMs() > 0"));
		assertTrue(body.contains("unlockThrottle.recordFailure()"));
		assertTrue(body.contains("unlockThrottle.reset()"));
		assertTrue(body.contains("enforceUnlockTimeFloor(start)"));
		int definition = end + "private boolean verifyPasswordUnthrottled("
				.length();
		assertTrue("no other caller bypasses the throttle",
				s.indexOf("verifyPasswordUnthrottled(", definition) < 0);
	}
}
