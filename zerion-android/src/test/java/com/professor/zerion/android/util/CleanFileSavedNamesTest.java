package com.professor.zerion.android.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CleanFileSavedNamesTest {

	private static final String WORD = "application/vnd.openxmlformats"
			+ "-officedocument.wordprocessingml.document";
	private static final String SHEET = "application/vnd.openxmlformats"
			+ "-officedocument.spreadsheetml.sheet";
	private static final String SLIDES = "application/vnd.openxmlformats"
			+ "-officedocument.presentationml.presentation";

	@Test
	public void aCleanedOfficeDocumentKeepsAnExtensionOtherAppsKnow() {
		assertEquals("docx", SavedFileNames.extension(WORD));
		assertEquals("xlsx", SavedFileNames.extension(SHEET));
		assertEquals("pptx", SavedFileNames.extension(SLIDES));
		assertEquals("docx", SavedFileNames.extension(
				WORD.toUpperCase(java.util.Locale.ROOT) + "; charset=x"));
		assertEquals("", SavedFileNames.extension(
				"application/vnd.openxmlformats-officedocument"));
		String name = SavedFileNames.named("Document", WORD, 0L);
		assertTrue(name, name.endsWith(".docx"));
		assertTrue(name, name.startsWith("Document "));
	}
}
