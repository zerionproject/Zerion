package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CleanPdfWriterTest {

	private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3,
			(byte) 0xFF, (byte) 0xD9};

	@Test
	public void theDocumentHoldsOnlyPagesAndNothingAboutItsOrigin()
			throws Exception {
		String pdf = write(2);

		assertTrue(pdf.startsWith("%PDF-1.4\n"));
		assertTrue(pdf.endsWith("%%EOF\n"));
		assertTrue(pdf.contains("/Type /Pages /Kids [5 0 R 8 0 R] /Count 2"));
		for (String key : new String[] {"/Info", "/ID", "/Producer",
				"/Creator", "/Author", "/Title", "/Subject", "/Keywords",
				"/CreationDate", "/ModDate", "/Metadata", "/XMP",
				"/EmbeddedFile", "/JavaScript", "/JS", "/OpenAction", "/AA",
				"/Annots", "/Names", "/Outlines", "/PieceInfo"}) {
			assertFalse(key, pdf.contains(key + " ")
					|| pdf.contains(key + "[") || pdf.contains(key + "<"));
		}
		assertEquals(2, CleanPdfPages.read(
				pdf.getBytes(StandardCharsets.ISO_8859_1)).size());
	}

	@Test
	public void everyCrossReferencePointsAtItsObject() throws Exception {
		String pdf = write(3);
		int xref = Integer.parseInt(find(pdf, "startxref\n(\\d+)\n"));
		assertTrue(pdf.startsWith("xref\n0 ", xref));
		Matcher size = Pattern.compile("xref\n0 (\\d+)\n").matcher(pdf);
		assertTrue(size.find());
		int count = Integer.parseInt(size.group(1));
		assertEquals(1 + 2 + 3 * 3, count);
		int row = size.end() + 20;
		for (int n = 1; n < count; n++) {
			int offset = Integer.parseInt(pdf.substring(row, row + 10));
			assertTrue("object " + n,
					pdf.startsWith(n + " 0 obj\n", offset));
			row += 20;
		}
		assertTrue(pdf.contains("/Size " + count + " /Root 1 0 R >>"));
	}

	@Test
	public void theImageStreamLengthMatchesItsBytes() throws Exception {
		String pdf = write(1);
		assertTrue(pdf.contains("/Filter /DCTDecode /Length " + JPEG.length
				+ " >>\nstream\n" + new String(JPEG,
				StandardCharsets.ISO_8859_1) + "\nendstream"));
	}

	@Test
	public void aDocumentOverTheLimitIsRefused() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CleanPdfWriter w = new CleanPdfWriter(out, 300);
		try {
			for (int i = 0; i < 10; i++) {
				w.addPage(JPEG, 10, 10, 100f, 100f);
			}
			w.finish();
			fail();
		} catch (CleanPdfWriter.TooLargeException expected) {
		}
	}

	@Test
	public void onlyJpegPagesWithASaneSizeAreTaken() throws Exception {
		CleanPdfWriter w = new CleanPdfWriter(new ByteArrayOutputStream(),
				1 << 20);
		refused(w, new byte[] {1, 2, 3, 4}, 100f);
		refused(w, JPEG, 0f);
		refused(w, JPEG, Float.NaN);
		refused(w, JPEG, 20000f);
		try {
			w.finish();
			fail("a document without pages was written");
		} catch (IOException expected) {
		}
	}

	private static void refused(CleanPdfWriter w, byte[] jpeg, float size) {
		try {
			w.addPage(jpeg, 10, 10, size, size);
			fail();
		} catch (IOException expected) {
		}
	}

	private static String write(int pages) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CleanPdfWriter w = new CleanPdfWriter(out, 1 << 20);
		for (int i = 0; i < pages; i++) {
			w.addPage(JPEG, 20, 30, 595.5f, 842f);
		}
		w.finish();
		assertEquals(pages, w.getPageCount());
		return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
	}

	private static String find(String s, String regex) {
		Matcher m = Pattern.compile(regex).matcher(s);
		assertTrue(m.find());
		return m.group(1);
	}
}
