package com.professor.zerion.android.attachment;

import com.professor.zerion.R;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CleanFilePolicyTest {

	private static byte[] zip(String... names) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zout = new ZipOutputStream(out)) {
			for (String n : names) {
				zout.putNextEntry(new ZipEntry(n));
				zout.write(("<" + n + "/>").getBytes(StandardCharsets.UTF_8));
				zout.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] isoMedia(String brand) {
		byte[] d = new byte[32];
		d[3] = 24;
		d[4] = 'f';
		d[5] = 't';
		d[6] = 'y';
		d[7] = 'p';
		for (int i = 0; i < 4; i++) d[8 + i] = (byte) brand.charAt(i);
		return d;
	}

	@Test
	public void aZipWithContentTypesIsAnOfficeDocument() throws Exception {
		byte[] word = zip("[Content_Types].xml", "_rels/.rels",
				"word/document.xml", "docProps/core.xml");
		assertTrue(CleanFilePolicy.isZip(word));
		assertTrue(CleanFilePolicy.isOfficeDocument(word));
		assertEquals(CleanFilePolicy.Cleaner.OFFICE,
				CleanFilePolicy.cleanerFor(word));
		assertEquals(CleanFilePolicy.WORD,
				CleanFilePolicy.officeMimeType(word));
		assertEquals(CleanFilePolicy.SHEET, CleanFilePolicy.officeMimeType(
				zip("[Content_Types].xml", "xl/workbook.xml")));
		assertEquals(CleanFilePolicy.SLIDES, CleanFilePolicy.officeMimeType(
				zip("[CONTENT_TYPES].XML", "ppt/presentation.xml")));
	}

	@Test
	public void aPlainZipOrAnythingElseGoesToTheSharedCleaner()
			throws Exception {
		byte[] plainZip = zip("photo.jpg", "notes.txt");
		assertTrue(CleanFilePolicy.isZip(plainZip));
		assertFalse(CleanFilePolicy.isOfficeDocument(plainZip));
		assertEquals(CleanFilePolicy.Cleaner.SHARED,
				CleanFilePolicy.cleanerFor(plainZip));
		byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
				0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
		assertEquals(CleanFilePolicy.Cleaner.SHARED,
				CleanFilePolicy.cleanerFor(jpeg));
		byte[] pdf = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
		assertEquals(CleanFilePolicy.Cleaner.SHARED,
				CleanFilePolicy.cleanerFor(pdf));
		byte[] pkButNotZip = {'P', 'K', 7, 8, 0, 0};
		assertFalse(CleanFilePolicy.isOfficeDocument(pkButNotZip));
		assertFalse(CleanFilePolicy.isOfficeDocument(new byte[0]));
		byte[] truncated = new byte[40];
		System.arraycopy(plainZip, 0, truncated, 0, 40);
		assertFalse(CleanFilePolicy.isOfficeDocument(truncated));
	}

	@Test
	public void onlyBitmapAvifAndHeifMayBeDecodedInTheAppProcess() {
		byte[] bmp = new byte[32];
		bmp[0] = 'B';
		bmp[1] = 'M';
		bmp[14] = 40;
		assertTrue(CleanFilePolicy.mayDecodeInAppProcess(bmp));
		assertTrue(CleanFilePolicy.mayDecodeInAppProcess(isoMedia("avif")));
		assertTrue(CleanFilePolicy.mayDecodeInAppProcess(isoMedia("heic")));
		assertTrue(CleanFilePolicy.mayDecodeInAppProcess(isoMedia("mif1")));
		assertTrue(CleanFilePolicy.isHeif(isoMedia("heix")));
		assertFalse(CleanFilePolicy.isHeif(isoMedia("avif")));
		assertFalse(CleanFilePolicy.mayDecodeInAppProcess(isoMedia("isom")));
		assertFalse(CleanFilePolicy.mayDecodeInAppProcess(isoMedia("mp42")));
		byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
		assertFalse(CleanFilePolicy.mayDecodeInAppProcess(tiff));
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0,
				13, 'I', 'H', 'D', 'R'};
		assertFalse(CleanFilePolicy.mayDecodeInAppProcess(png));
		assertFalse(CleanFilePolicy.mayDecodeInAppProcess(new byte[0]));
	}

	@Test
	public void theOutcomeFollowsTheCleanerAndTheCleanedType() {
		assertEquals(CleanFilePolicy.Outcome.OFFICE, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.OFFICE, CleanFilePolicy.WORD));
		assertEquals(CleanFilePolicy.Outcome.IMAGE, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED, "image/jpeg"));
		assertEquals(CleanFilePolicy.Outcome.IMAGE, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED, "Image/PNG"));
		assertEquals(CleanFilePolicy.Outcome.MEDIA, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED, "video/mp4"));
		assertEquals(CleanFilePolicy.Outcome.MEDIA, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED, "audio/ogg"));
		assertEquals(CleanFilePolicy.Outcome.PDF, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED, "application/pdf"));
		assertEquals(CleanFilePolicy.Outcome.TEXT, CleanFilePolicy
				.outcomeFor(CleanFilePolicy.Cleaner.SHARED,
						"text/plain; charset=utf-8"));
		for (String unknown : new String[] {"application/zip",
				"application/octet-stream", "", null}) {
			try {
				CleanFilePolicy.outcomeFor(CleanFilePolicy.Cleaner.SHARED,
						unknown);
				fail("no wording for " + unknown);
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void everyOutcomeHasItsOwnWording() {
		assertEquals(R.string.clean_file_done_office,
				CleanFilePolicy.explanation(CleanFilePolicy.Outcome.OFFICE));
		assertEquals(R.string.clean_file_done_image,
				CleanFilePolicy.explanation(CleanFilePolicy.Outcome.IMAGE));
		assertEquals(R.string.clean_file_done_media,
				CleanFilePolicy.explanation(CleanFilePolicy.Outcome.MEDIA));
		assertEquals(R.string.clean_file_done_pdf,
				CleanFilePolicy.explanation(CleanFilePolicy.Outcome.PDF));
		assertEquals(R.string.clean_file_done_text,
				CleanFilePolicy.explanation(CleanFilePolicy.Outcome.TEXT));
		java.util.Set<Integer> distinct = new java.util.HashSet<>();
		for (CleanFilePolicy.Outcome o : CleanFilePolicy.Outcome.values()) {
			distinct.add(CleanFilePolicy.explanation(o));
		}
		assertEquals(CleanFilePolicy.Outcome.values().length, distinct.size());
	}
}
