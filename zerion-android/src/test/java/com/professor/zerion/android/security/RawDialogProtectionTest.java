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
