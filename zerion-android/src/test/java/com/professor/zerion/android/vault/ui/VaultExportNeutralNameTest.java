package com.professor.zerion.android.vault.ui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VaultExportNeutralNameTest {

	private static String source() throws Exception {
		return new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/vault/ui/"
						+ "VaultDocumentsFragment.java")),
				StandardCharsets.UTF_8);
	}

	@Test
	public void theEncryptedExportIsNotWrittenToAnAppFolderInDownloads()
			throws Exception {
		String s = source();
		assertFalse("the export writes into the public Downloads folder",
				s.contains("DIRECTORY_DOWNLOADS"));
		assertFalse("the export creates a folder named after the app",
				s.contains("\"Zerion\""));
	}

	@Test
	public void theEncryptedExportIsNotNamedAfterTheItem() throws Exception {
		String s = source();
		int start = s.indexOf("private void performEncryptedExport(");
		int end = s.indexOf("\n\t}", start);
		assertTrue(start > 0 && end > start);
		String body = s.substring(start, end);
		assertFalse("the exported file is named after the item",
				body.contains("safeExportName(item.name)"));
		assertFalse(body.contains("item.name + "));
	}

	@Test
	public void neutralNamesSayNothingAndDoNotRepeat() throws Exception {
		java.lang.reflect.Method name = VaultDocumentsFragment.class
				.getDeclaredMethod("neutralExportName", Random.class);
		name.setAccessible(true);
		Random random = new Random(7);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			String n = (String) name.invoke(null, random);
			assertTrue(n, n.matches("export-[0-9a-f]{8}\\.zenc"));
			seen.add(n);
		}
		assertTrue(seen.size() > 95);
	}
}
