package com.professor.zerion.android;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReleaseSurfaceTest {

	private static List<Path> mainSources() throws IOException {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main"))) {
			for (Path p : (Iterable<Path>) files::iterator) {
				String n = p.toString();
				if (n.endsWith(".java") || n.endsWith(".xml")) out.add(p);
			}
		}
		return out;
	}

	@Test
	public void theTestDataScreenIsNotInReleaseSources() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path p : mainSources()) {
			String n = p.getFileName().toString();
			if (n.equals("TestDataActivity.java")
					|| n.equals("activity_test_data.xml")) {
				offenders.add(p.toString());
			}
		}
		assertTrue("debug-only code in release sources: " + offenders,
				offenders.isEmpty());
		assertTrue(Files.exists(Paths.get("src/debug/java/com/professor/"
				+ "zerion/android/test/TestDataActivity.java")));
	}

	@Test
	public void noProviderAuthorityIsHardCoded() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path p : mainSources()) {
			if (!p.toString().endsWith(".java")) continue;
			String s = new String(Files.readAllBytes(p),
					StandardCharsets.UTF_8);
			if (s.contains("\"com.professor.zerion.fileprovider\"")
					|| s.contains("\"com.professor.zerion.vaultshare\"")) {
				offenders.add(p.toString());
			}
		}
		assertTrue("hard-coded authorities: " + offenders,
				offenders.isEmpty());
	}

	@Test
	public void releaseCodeDoesNotNameTheDebugScreenAsAClass()
			throws IOException {
		String s = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/settings/"
						+ "SettingsFragment.java")), StandardCharsets.UTF_8);
		assertFalse(s.contains("TestDataActivity.class"));
	}
}
