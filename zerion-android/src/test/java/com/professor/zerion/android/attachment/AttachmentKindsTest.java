package com.professor.zerion.android.attachment;

import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AttachmentKindsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	private String named(String type) {
		return AttachmentKinds.describe(ctx, type);
	}

	@Test
	public void commonTypesAreNamedInPlainWords() {
		assertEquals("PDF document", named("application/pdf"));
		assertEquals("Text document", named("text/plain; charset=utf-8"));
		assertEquals("Text document", named("text/markdown"));
		assertEquals("Image", named("image/jpeg"));
		assertEquals("Video", named("video/mp4"));
		assertEquals("Audio file", named("audio/ogg"));
		assertEquals("Spreadsheet", named("application/vnd.openxmlformats"
				+ "-officedocument.spreadsheetml.sheet"));
		assertEquals("Spreadsheet", named("text/csv"));
		assertEquals("Presentation", named("application/vnd.ms-powerpoint"));
		assertEquals("Document", named("application/msword"));
		assertEquals("Archive", named("application/zip"));
		assertEquals("File", named("application/octet-stream"));
		assertEquals("File", AttachmentKinds.describe(ctx, null));
	}

	@Test
	public void noTypeIsEverShownAsItsRawName() {
		for (String type : new String[] {"application/pdf", "PDF/PDF",
				"application/x-foo", "chemical/x-pdb", "model/gltf+json",
				"Application/PDF", "x/y", "", " ", "application/",
				"image/svg+xml", "message/rfc822", "text/html"}) {
			String shown = named(type);
			assertFalse(type + " was shown as " + shown, shown.contains("/"));
			assertFalse(type + " was shown as " + shown,
					shown.toLowerCase().contains("application"));
		}
	}

	@Test
	public void aRowSaysTheKindAndTheSize() {
		String row = AttachmentKinds.describe(ctx, "application/pdf",
				1_200_000L);
		assertTrue(row, row.startsWith("PDF document, "));
		assertTrue(row, row.contains("MB"));
		assertEquals("PDF document",
				AttachmentKinds.describe(ctx, "application/pdf", 0L));
	}
}
