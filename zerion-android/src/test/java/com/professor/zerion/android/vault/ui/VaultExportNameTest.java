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

	@Test
	public void aPlainExportGetsTheSameCleanNameAsEverySavedCopy() {
		assertEquals("invoicefdp.pdf",
				VaultDocumentsFragment.safeExportName("invoice\u202Efdp.pdf"));
		assertEquals("passport.pdf", VaultDocumentsFragment.safeExportName(
				"pass\u200Bport.pdf"));
		assertEquals("_CON.txt", VaultDocumentsFragment.safeExportName(
				"CON.txt"));
		assertEquals("hidden", VaultDocumentsFragment.safeExportName(
				".hidden"));
		assertEquals("rf", VaultDocumentsFragment.safeExportName("-rf"));
		assertEquals("a_b.txt", VaultDocumentsFragment.safeExportName(
				"a<>b.txt"));
		assertEquals("vault-item", VaultDocumentsFragment.safeExportName(
				"...."));
		assertEquals("vault-item", VaultDocumentsFragment.safeExportName(
				"\u202E\u200B"));
		String longName = new String(new char[300]).replace('\0', 'n')
				+ ".pdf";
		String cut = VaultDocumentsFragment.safeExportName(longName);
		assertEquals(64, cut.length());
		assertTrue(cut, cut.endsWith(".pdf"));
	}
}
