package com.professor.zerion.android.attachment;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CleanFileShareCopyTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aSecondShareInTheSameSecondGetsItsOwnName()
			throws IOException {
		File dir = tmp.newFolder();
		SecureRandom random = new SecureRandom();
		String name = "Document 2026-11-19_101500.docx";
		File first = CleanFileActivity.unusedFile(dir, name, random);
		assertEquals(name, first.getName());
		assertTrue(first.createNewFile());
		File second = CleanFileActivity.unusedFile(dir, name, random);
		assertNotEquals(first, second);
		assertTrue(second.getName(), second.getName().matches(
				"Document 2026-11-19_101500-[0-9a-f]{4}\\.docx"));
		assertTrue(second.createNewFile());
		assertTrue("the first copy is left for its reader", first.exists());
		File third = CleanFileActivity.unusedFile(dir, name, random);
		assertNotEquals(first, third);
		assertNotEquals(second, third);
	}

	@Test
	public void aNameWithoutAnExtensionGetsTheSuffixAtItsEnd()
			throws IOException {
		File dir = tmp.newFolder();
		SecureRandom random = new SecureRandom();
		File first = CleanFileActivity.unusedFile(dir, "Document", random);
		assertEquals("Document", first.getName());
		assertTrue(first.createNewFile());
		File second = CleanFileActivity.unusedFile(dir, "Document", random);
		assertTrue(second.getName(),
				second.getName().matches("Document-[0-9a-f]{4}"));
	}
}
