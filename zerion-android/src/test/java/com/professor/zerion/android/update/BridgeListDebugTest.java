package com.professor.zerion.android.update;

import org.junit.Test;
import org.zerionproject.tor.BridgeListKeys;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class BridgeListDebugTest {

	private static final File RELEASE = new File(
			"src/release/java/com/professor/zerion/android/update/"
					+ "BridgeListDebug.java");
	private static final File DEBUG = new File(
			"src/debug/java/com/professor/zerion/android/update/"
					+ "BridgeListDebug.java");

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	private static void sources(File dir, List<File> out) {
		File[] files = dir.listFiles();
		if (files == null) return;
		for (File f : files) {
			if (f.isDirectory()) sources(f, out);
			else if (f.getName().endsWith(".java")
					|| f.getName().endsWith(".xml")
					|| f.getName().endsWith(".kt")) {
				out.add(f);
			}
		}
	}

	@Test
	public void theDebugBuildHasATestFolderAndADevelopmentKey() {
		assertEquals("bridge-test", BridgeListDebug.TEST_DIR);
		assertEquals(1, BridgeListDebug.LOCAL_KEYS.size());
		String key = BridgeListDebug.LOCAL_KEYS.get(0);
		assertNotEquals(BridgeListKeys.PINNED_KEY, key);
		assertEquals(32, BridgeListKeys.decodeKey(key).length);
		BridgeListKeys keys =
				BridgeListKeys.withLocalKeys(BridgeListDebug.LOCAL_KEYS);
		assertEquals("a downloaded list is checked against the pinned key"
				+ " alone", 1, keys.forNetwork().size());
		assertArrayEquals(BridgeListKeys.decodeKey(BridgeListKeys.PINNED_KEY),
				keys.forNetwork().get(0));
	}

	@Test
	public void theReleaseBuildHasNeitherTheTestFolderNorTheKey()
			throws IOException {
		assertTrue(RELEASE.getAbsolutePath(), RELEASE.isFile());
		assertTrue(DEBUG.getAbsolutePath(), DEBUG.isFile());
		String release = read(RELEASE);
		String key = BridgeListDebug.LOCAL_KEYS.get(0);
		assertTrue(release.contains("TEST_DIR = null;"));
		assertTrue(release.contains("LOCAL_KEYS = Collections.emptyList();"));
		assertFalse(release.contains(key));
		assertFalse(release.contains("bridge-test"));
		assertFalse("only the pinned key may look like a key in release",
				release.matches("(?s).*[A-Za-z0-9+/]{43}=.*"));

		List<File> shipped = new ArrayList<>();
		sources(new File("src/main"), shipped);
		sources(new File("src/release"), shipped);
		assertTrue(shipped.size() > 100);
		for (File f : shipped) {
			String text = read(f);
			assertFalse(f.getPath(), text.contains(key));
			assertFalse(f.getPath(), text.contains("\"bridge-test\""));
		}
	}

	@Test
	public void theReleaseClassDeclaresTheSameFields() throws IOException {
		Set<String> names = new HashSet<>();
		for (Field f : BridgeListDebug.class.getDeclaredFields()) {
			if (!f.isSynthetic()) names.add(f.getName());
		}
		assertEquals(new HashSet<>(Arrays.asList("TEST_DIR", "LOCAL_KEYS")),
				names);
		String release = read(RELEASE);
		for (String n : names) assertTrue(n, release.contains(" " + n + " = "));
	}
}
