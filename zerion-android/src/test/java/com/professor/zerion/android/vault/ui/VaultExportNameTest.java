package com.professor.zerion.android.vault.ui;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VaultExportNameTest {

	@Test
	public void providerChosenNamesCannotLeaveTheExportDirectory()
			throws Exception {
		File dir = new File("exports").getCanonicalFile();
		for (String name : new String[] {"../../files/x", "..", ".",
				"/data/data/pkg/files/x", "a/../../b", "x\u0000y", "",
				"C:\\temp\\x", "..\\..\\x", "ok name.pdf"}) {
			String safe = VaultDocumentsFragment.safeExportName(name);
			assertFalse(name, safe.isEmpty());
			assertFalse(name, safe.contains("/"));
			assertFalse(name, safe.contains("\\"));
			assertFalse(name, safe.equals("..") || safe.equals("."));
			File out = new File(dir, safe + ".zenc").getCanonicalFile();
			assertEquals(name, dir, out.getParentFile());
		}
		assertEquals("ok name.pdf",
				VaultDocumentsFragment.safeExportName("ok name.pdf"));
		assertTrue(VaultDocumentsFragment.safeExportName(
				new String(new char[500]).replace('\0', 'a')).length() <= 120);
	}
}
