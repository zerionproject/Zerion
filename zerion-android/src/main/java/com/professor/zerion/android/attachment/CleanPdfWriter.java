package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@NotNullByDefault
public final class CleanPdfWriter {

	public static final class TooLargeException extends IOException {

		TooLargeException() {
			super("cleaned document too large");
		}
	}

	private static final int CATALOG = 1;
	private static final int PAGES = 2;
	private static final float MAX_POINTS = 14400f;

	private final OutputStream out;
	private final long maxBytes;
	private final List<Long> offsets = new ArrayList<>();
	private final List<Integer> pages = new ArrayList<>();
	private long written = 0;
	private boolean finished = false;

	public CleanPdfWriter(OutputStream out, long maxBytes) throws IOException {
		this.out = out;
		this.maxBytes = maxBytes;
		offsets.add(0L);
		offsets.add(0L);
		write(ascii("%PDF-1.4\n"));
		write(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF,
				(byte) 0xD3, '\n'});
	}

	public int getPageCount() {
		return pages.size();
	}

	public void addPage(byte[] jpeg, int pixelWidth, int pixelHeight,
			float pointWidth, float pointHeight) throws IOException {
		addPage(jpeg, 0, jpeg.length, pixelWidth, pixelHeight, pointWidth,
				pointHeight);
	}

	public void addPage(byte[] source, int offset, int length,
			int pixelWidth, int pixelHeight, float pointWidth,
			float pointHeight) throws IOException {
		if (finished) throw new IllegalStateException();
		if (offset < 0 || length < 0 || length > source.length - offset
				|| !isJpeg(source, offset, length)) {
			throw new IOException("page image is not JPEG");
		}
		if (pixelWidth <= 0 || pixelHeight <= 0) {
			throw new IOException("empty page image");
		}
		float w = points(pointWidth);
		float h = points(pointHeight);
		int image = begin();
		write(ascii("<< /Type /XObject /Subtype /Image /Width " + pixelWidth
				+ " /Height " + pixelHeight
				+ " /ColorSpace /DeviceRGB /BitsPerComponent 8"
				+ " /Filter /DCTDecode /Length " + length
				+ " >>\nstream\n"));
		write(source, offset, length);
		write(ascii("\nendstream\nendobj\n"));
		byte[] drawing = ascii("q " + number(w) + " 0 0 " + number(h)
				+ " 0 0 cm /Im0 Do Q");
		int content = begin();
		write(ascii("<< /Length " + drawing.length + " >>\nstream\n"));
		write(drawing);
		write(ascii("\nendstream\nendobj\n"));
		int page = begin();
		write(ascii("<< /Type /Page /Parent " + PAGES + " 0 R /MediaBox [0 0 "
				+ number(w) + " " + number(h) + "] /Resources << /XObject"
				+ " << /Im0 " + image + " 0 R >> >> /Contents " + content
				+ " 0 R >>\nendobj\n"));
		pages.add(page);
	}

	public void finish() throws IOException {
		if (finished) throw new IllegalStateException();
		if (pages.isEmpty()) throw new IOException("no pages");
		finished = true;
		StringBuilder kids = new StringBuilder();
		for (int p : pages) {
			if (kids.length() > 0) kids.append(' ');
			kids.append(p).append(" 0 R");
		}
		offsets.set(PAGES - 1, written);
		write(ascii(PAGES + " 0 obj\n<< /Type /Pages /Kids [" + kids
				+ "] /Count " + pages.size() + " >>\nendobj\n"));
		offsets.set(CATALOG - 1, written);
		write(ascii(CATALOG + " 0 obj\n<< /Type /Catalog /Pages " + PAGES
				+ " 0 R >>\nendobj\n"));
		long xref = written;
		int size = offsets.size() + 1;
		StringBuilder table = new StringBuilder();
		table.append("xref\n0 ").append(size).append('\n');
		table.append("0000000000 65535 f \n");
		for (long offset : offsets) {
			table.append(String.format(Locale.ROOT, "%010d 00000 n \n",
					offset));
		}
		table.append("trailer\n<< /Size ").append(size).append(" /Root ")
				.append(CATALOG).append(" 0 R >>\nstartxref\n").append(xref)
				.append("\n%%EOF\n");
		write(ascii(table.toString()));
		out.flush();
	}

	private int begin() throws IOException {
		offsets.add(written);
		int number = offsets.size();
		write(ascii(number + " 0 obj\n"));
		return number;
	}

	private void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}

	private void write(byte[] b, int off, int len) throws IOException {
		if (written + len > maxBytes) throw new TooLargeException();
		out.write(b, off, len);
		written += len;
	}

	private static float points(float p) throws IOException {
		if (!(p >= 1f) || p > MAX_POINTS) {
			throw new IOException("page size out of range");
		}
		return p;
	}

	private static String number(float f) {
		return String.format(Locale.ROOT, "%.2f", f);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	static boolean isJpeg(byte[] b) {
		return isJpeg(b, 0, b.length);
	}

	static boolean isJpeg(byte[] b, int off, int len) {
		int end = off + len;
		return len >= 4 && (b[off] & 0xFF) == 0xFF
				&& (b[off + 1] & 0xFF) == 0xD8 && (b[end - 2] & 0xFF) == 0xFF
				&& (b[end - 1] & 0xFF) == 0xD9;
	}
}
