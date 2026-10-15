package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public final class CleanPdfPages {

	public static final class Page {

		final byte[] source;
		final int offset;
		final int length;
		final int width;
		final int height;
		final float pointWidth;
		final float pointHeight;

		Page(byte[] source, int offset, int length, int width, int height,
				float pointWidth, float pointHeight) {
			this.source = source;
			this.offset = offset;
			this.length = length;
			this.width = width;
			this.height = height;
			this.pointWidth = pointWidth;
			this.pointHeight = pointHeight;
		}

		byte[] jpeg() {
			return Arrays.copyOfRange(source, offset, offset + length);
		}
	}

	private static final int HEADER_LENGTH = 15;
	private static final int MAX_PAGE_LINE = 200;

	private CleanPdfPages() {
	}

	public static List<Page> read(byte[] pdf) throws IOException {
		Cursor c = new Cursor(pdf);
		c.pos = HEADER_LENGTH;
		List<Page> pages = new ArrayList<>();
		Matching same = new Matching(pdf);
		CleanPdfWriter writer = new CleanPdfWriter(same, pdf.length);
		while (c.atImage()) {
			c.number();
			c.expect(" 0 obj\n<< /Type /XObject /Subtype /Image /Width ");
			int width = (int) c.number();
			c.expect(" /Height ");
			int height = (int) c.number();
			c.expect(" /ColorSpace /DeviceRGB /BitsPerComponent 8"
					+ " /Filter /DCTDecode /Length ");
			long length = c.number();
			c.expect(" >>\nstream\n");
			if (length < 1 || length > pdf.length - c.pos) {
				throw new IOException("page image out of range");
			}
			int offset = c.pos;
			c.pos += (int) length;
			c.expect("\nendstream\nendobj\n");
			c.number();
			c.expect(" 0 obj\n<< /Length ");
			c.number();
			c.expect(" >>\nstream\nq ");
			float pointWidth = c.decimal();
			c.expect(" 0 0 ");
			float pointHeight = c.decimal();
			c.expect(" 0 0 cm /Im0 Do Q\nendstream\nendobj\n");
			c.number();
			c.expect(" 0 obj\n<< /Type /Page ");
			c.skipLine(MAX_PAGE_LINE);
			c.expect("endobj\n");
			try {
				writer.addPage(pdf, offset, (int) length, width, height,
						pointWidth, pointHeight);
			} catch (CleanPdfWriter.TooLargeException e) {
				throw new IOException("not a document written by this app");
			}
			pages.add(new Page(pdf, offset, (int) length, width, height,
					pointWidth, pointHeight));
		}
		writer.finish();
		return checked(pages, same);
	}

	private static List<Page> checked(List<Page> pages, Matching same)
			throws IOException {
		if (!same.complete()) {
			throw new IOException("not a document written by this app");
		}
		return pages;
	}

	private static final class Matching extends OutputStream {

		private final byte[] expected;
		private int pos = 0;
		private boolean differs = false;

		Matching(byte[] expected) {
			this.expected = expected;
		}

		@Override
		public void write(int b) {
			if (pos >= expected.length || expected[pos] != (byte) b) {
				differs = true;
			}
			pos++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			if (len > expected.length - pos) {
				differs = true;
				pos += len;
				return;
			}
			for (int i = 0; i < len && !differs; i++) {
				if (expected[pos + i] != b[off + i]) differs = true;
			}
			pos += len;
		}

		boolean complete() {
			return !differs && pos == expected.length;
		}
	}

	private static final class Cursor {

		private final byte[] b;
		private int pos;

		Cursor(byte[] b) {
			this.b = b;
		}

		boolean atImage() {
			int p = pos;
			while (p < b.length && b[p] >= '0' && b[p] <= '9') p++;
			if (p == pos || p - pos > 6) return false;
			return startsWith(p, " 0 obj\n<< /Type /XObject ");
		}

		void expect(String s) throws IOException {
			if (!startsWith(pos, s)) {
				throw new IOException("unexpected document structure");
			}
			pos += s.length();
		}

		long number() throws IOException {
			int start = pos;
			long value = 0;
			while (pos < b.length && b[pos] >= '0' && b[pos] <= '9') {
				if (pos - start >= 9) throw new IOException("number too long");
				value = value * 10 + (b[pos] - '0');
				pos++;
			}
			if (pos == start) throw new IOException("number expected");
			return value;
		}

		float decimal() throws IOException {
			long whole = number();
			expect(".");
			int start = pos;
			long fraction = number();
			if (pos - start != 2) throw new IOException("two decimals expected");
			return Float.parseFloat(whole + "." + (fraction < 10 ? "0" : "")
					+ fraction);
		}

		void skipLine(int max) throws IOException {
			int end = Math.min(b.length, pos + max);
			for (int p = pos; p < end; p++) {
				if (b[p] == '\n') {
					pos = p + 1;
					return;
				}
			}
			throw new IOException("line too long");
		}

		private boolean startsWith(int at, String s) {
			byte[] want = s.getBytes(StandardCharsets.US_ASCII);
			if (at < 0 || want.length > b.length - at) return false;
			for (int i = 0; i < want.length; i++) {
				if (b[at + i] != want[i]) return false;
			}
			return true;
		}
	}
}
