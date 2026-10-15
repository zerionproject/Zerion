package com.professor.zerion.android.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CameraFileNamesTest {

	private static final Pattern RANDOM_UUID = Pattern.compile(
			"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

	@Test
	public void namesKeepTheirPrefixAndExtensionAndCarryNoTime() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			String name = CameraFileNames.random("temp_", ".jpg");
			assertTrue(name.startsWith("temp_"));
			assertTrue(name.endsWith(".jpg"));
			String middle = name.substring(5, name.length() - 4);
			assertTrue(name, RANDOM_UUID.matcher(middle).matches());
			seen.add(name);
		}
		assertEquals(200, seen.size());
	}

	@Test
	public void cameraFilesAreNotNamedAfterTheClock() throws Exception {
		Pattern timed = Pattern.compile(
				"\"(temp_|final_|capture_)\"\\s*\\+\\s*System\\.currentTimeMillis");
		Pattern random = Pattern.compile(
				"CameraFileNames\\.random\\(\"(temp_|final_|capture_)\"");
		String[] sources = {
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "ConversationActivity.java",
				"src/main/java/com/professor/zerion/android/vault/ui/"
						+ "VaultGalleryFragment.java",
		};
		int named = 0;
		for (String path : sources) {
			String src = new String(Files.readAllBytes(Paths.get(path)),
					StandardCharsets.UTF_8);
			assertFalse(path, timed.matcher(src).find());
			Matcher m = random.matcher(src);
			while (m.find()) named++;
		}
		assertEquals(4, named);
	}
}
