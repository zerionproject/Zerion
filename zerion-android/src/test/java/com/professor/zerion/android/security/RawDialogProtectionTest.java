package com.professor.zerion.android.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * A2-REG-AND-01: a raw Dialog is outside the FLAG_SECURE policy that alert
 * dialogs and dialog fragments get automatically, so every file that builds
 * one must route it through the secret-protecting helper. The gallery viewer
 * and the avatar viewer show decrypted content in such windows.
 */
public class RawDialogProtectionTest {

	@Test
	public void everyRawDialogIsProtected() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
			for (Path p : (Iterable<Path>) files::iterator) {
				if (!p.toString().endsWith(".java")) continue;
				String s = new String(Files.readAllBytes(p),
						StandardCharsets.UTF_8);
				boolean raw = s.contains("new android.app.Dialog(")
						|| s.contains("new Dialog(");
				if (raw && !s.contains("SecureDialogs.protectSecret(")) {
					offenders.add(p.toString());
				}
			}
		}
		assertTrue("raw dialogs without FLAG_SECURE: " + offenders,
				offenders.isEmpty());
	}
}
