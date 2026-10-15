package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.professor.zerion.android.attachment.ImageScrubberTest.cleanJpeg;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class CleanPdfPagesTest {

	@Test
	public void theRendererOutputIsReadBackPageByPage() throws Exception {
		List<CleanPdfPages.Page> pages =
				CleanPdfPages.read(written(cleanJpeg(), 3));
		assertEquals(3, pages.size());
		for (CleanPdfPages.Page p : pages) {
			assertArrayEquals(cleanJpeg(), p.jpeg());
			assertEquals(16, p.width);
			assertEquals(24, p.height);
			assertEquals(595.28f, p.pointWidth, 0.001f);
			assertEquals(841.89f, p.pointHeight, 0.001f);
		}
	}

	@Test
	public void anythingThatIsNotExactlyWriterOutputIsRefused()
			throws Exception {
		byte[] clean = written(cleanJpeg(), 2);
		byte[] trailing = Arrays.copyOf(clean, clean.length + 1);
		trailing[clean.length] = '%';
		refused(trailing);
		String text = new String(clean, StandardCharsets.ISO_8859_1);
		for (String[] change : new String[][] {
				{"/Root 1 0 R", "/Root 1 0 R /Info 1 0 R"},
				{"/Count 2", "/Count 3"},
				{"%PDF-1.4", "%PDF-1.7"},
				{"/Im0 Do Q", "/Im0 Do q"},
				{"startxref", "startxreg"}}) {
			refused(text.replace(change[0], change[1])
					.getBytes(StandardCharsets.ISO_8859_1));
		}
		refused(new byte[0]);
		refused(Arrays.copyOf(clean, clean.length / 2));
		refused(("%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n"
				+ "trailer << /Root 1 0 R >>\n%%EOF\n")
				.getBytes(StandardCharsets.ISO_8859_1));
	}

	@Test
	public void aLengthPastTheEndIsRefusedWithoutReadingIt() throws Exception {
		String text = new String(written(cleanJpeg(), 1),
				StandardCharsets.ISO_8859_1);
		refused(text.replace("/Length " + cleanJpeg().length + " ",
				"/Length 999999999 ").getBytes(StandardCharsets.ISO_8859_1));
	}

	private static void refused(byte[] pdf) {
		try {
			CleanPdfPages.read(pdf);
			fail("accepted a document that is not writer output");
		} catch (IOException expected) {
		}
	}

	private static byte[] written(byte[] jpeg, int pages) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		CleanPdfWriter w = new CleanPdfWriter(out, 1 << 22);
		for (int i = 0; i < pages; i++) {
			w.addPage(jpeg, 16, 24, 595.28f, 841.89f);
		}
		w.finish();
		return out.toByteArray();
	}
}
