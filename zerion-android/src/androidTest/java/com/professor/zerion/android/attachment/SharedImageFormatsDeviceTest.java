package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SharedImageFormatsDeviceTest {

	private static final long MAX = 50L * 1024 * 1024;
	private static final byte[] MARKER = "ZtPlantedGps52.3702N4.8952E"
			.getBytes(StandardCharsets.US_ASCII);

	private static final String AVIF =
			"AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAAF0bWV0YQAAAAAAAAAh"
			+ "aGRscgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAAAAAAAOcGl0bQAAAAAAAQAAADpp"
			+ "bG9jAAAAAEQAAAMAAQAAAAEAAAL5AAAAQAACAAAAAQAAAZwAAAB2AAMAAAABAAAC"
			+ "EgAAAOcAAABtaWluZgAAAAAAAwAAABppbmZlAgAAAAABAABhdjAxQ29sb3IAAAAA"
			+ "GWluZmUCAAAAAAIAAEV4aWZFeGlmAAAAACxpbmZlAgAAAAADAABtaW1lWE1QAGFw"
			+ "cGxpY2F0aW9uL3JkZit4bWwAAAAAKGlyZWYAAAAAAAAADmNkc2MAAgABAAEAAAAO"
			+ "Y2RzYwADAAEAAQAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAAAAAgAAAAGAAA"
			+ "ABBwaXhpAAAAAAMICAgAAAAMYXYxQ4EADAAAAAATY29scm5jbHgAAQANAAaAAAAA"
			+ "F2lwbWEAAAAAAAAAAQABBAECgwQAAAGlbWRhdAAAAAZFeGlmAABNTQAqAAAACAAD"
			+ "AQ8AAgAAAA4AAAAyARAAAgAAAA8AAABAkoYAAgAAABwAAABQAAAAAFp0UGxhbnRl"
			+ "ZE1ha2UAWnRQbGFudGVkTW9kZWwAAFp0UGxhbnRlZEdwczUyLjM3MDJONC44OTUy"
			+ "RQA8eDp4bXBtZXRhIHhtbG5zOng9J2Fkb2JlOm5zOm1ldGEvJz48cmRmOlJERiB4"
			+ "bWxuczpyZGY9J2h0dHA6Ly93d3cudzMub3JnLzE5OTkvMDIvMjItcmRmLXN5bnRh"
			+ "eC1ucyMnPjxyZGY6RGVzY3JpcHRpb24geG1sbnM6ZXhpZj0naHR0cDovL25zLmFk"
			+ "b2JlLmNvbS9leGlmLzEuMC8nIGV4aWY6R1BTTGF0aXR1ZGU9J1p0UGxhbnRlZEdw"
			+ "czUyLjM3MDJONC44OTUyRScvPjwvcmRmOlJERj48L3g6eG1wbWV0YT4SAAoGGBE/"
			+ "dgQgMjQTQAIIIIQAOtX7/T9lTXOK5yEN3wvqcvBN3+OUa0WSXEIGuE0Cwa89APyE"
			+ "eTe8hvrLWySA";

	private static final String ANIMATED_GIF =
			"R0lGODlhGAAQAIEAAP8AQAAAQAAAAAAAACH/C1hNUCBEYXRhWE1Q/zx4OnhtcG1l"
			+ "dGE+PGV4aWY6R1BTTGF0aXR1ZGU+WnRQbGFudGVkR3BzNTIuMzcwMk40Ljg5NTJF"
			+ "PC9leGlmOkdQU0xhdGl0dWRlPjwveDp4bXBtZXRhPgH/"
			+ "/v38+/r5+Pf29fTz8vHw7+7t7Ovq6ejn5uXk4+Lh4N/e3dzb2tnY19bV1NPS0dDP"
			+ "zs3My8rJyMfGxcTDwsHAv769vLu6ubi3trW0s7KxsK+urayrqqmop6alpKOioaCf"
			+ "np2cm5qZmJeWlZSTkpGQj46NjIuKiYiHhoWEg4KBgH9+fXx7enl4d3Z1dHNycXBv"
			+ "bm1sa2ppaGdmZWRjYmFgX15dXFtaWVlYV1ZVVFNSUVBPTk1MS0pJSEdGRURDQkFA"
			+ "Pz49PDs6OTg3NjU0MzIxMC8uLSwrKikoJyYlJCMiISAfHh0cGxoZGBcWFRQTEhEQ"
			+ "Dw4NDAsKCQgHBgUEAwIBAAAh/wtJQ0NSR0JHMTAxMi5wcm9maWxlIHdyaXR0ZW4g"
			+ "YnkgWnRQbGFudGVkR3BzNTIuMzcwMk40Ljg5NTJFACH/C05FVFNDQVBFMi4wAwED"
			+ "AAAh/iNNYWRlIGF0IFp0UGxhbnRlZEdwczUyLjM3MDJONC44OTUyRQAh+QQECgAA"
			+ "ACwAAAAAGAAQAAAIQQABBAggkODAgggPKhxosGFChwsFPpwYUSFFiBgLVsy4UWLH"
			+ "jwYvilwIcmTIkh9NmkTJ8WTLkSpBsoQ5s2LMlgEBACH5BAULAAIALAAAAAAYABAA"
			+ "gf9QQABQQAAAAAAAAAg/AAMEACCQ4MCCCA8qJGiwYUKHCyNKhAjxocWJCCle1KgR"
			+ "o8SNICN2HJkxJEmBHk8yNAlSZUiWKWPGdOkR5siAACH5BAUMAAIALAAAAAAYABAA"
			+ "gf+gQACgQAAAAAAAAAg+AAMACCCQ4MCCCA8qTGiwIcOHDhc+lDiQYsSLBS1OxIhR"
			+ "o0WPHRuC3IgwJMmFI0GmNGly5cqNLzW2nBnzYkAAIfkEBQ0AAgAsAAAAABgAEACB"
			+ "/"
			+ "/BAAPBAAAAAAAAACEEAAQQIIJDgwIIIDyocaLBhQocLBT6cGFEhRYgYC1bMuFFix"
			+ "48GL4pcCHJkyJIfTZpEyfFky5EqQbKEObNizJYBAQA7dHJhaWxpbmcgWnRQbGFud"
			+ "GVkR3BzNTIuMzcwMk40Ljg5NTJF";

	private final Context ctx = ApplicationProvider.getApplicationContext();
	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private SharedMediaSanitizer.Cleaned share(byte[] data, String declared)
			throws IOException {
		File f = File.createTempFile("zt_formats_", ".bin", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), data);
		return new SharedMediaSanitizer(ctx).sanitize(Uri.fromFile(f),
				declared, data, MAX);
	}

	private static byte[] le(long v, int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) b[i] = (byte) (v >> (8 * i));
		return b;
	}

	private static void write(ByteArrayOutputStream out, byte[]... parts) {
		for (byte[] p : parts) out.write(p, 0, p.length);
	}

	private static byte[] bmp(int width, int height) {
		byte[] path = ("C:\\Users\\" + new String(MARKER,
				StandardCharsets.US_ASCII) + "\\profile.icc\0")
				.getBytes(StandardCharsets.US_ASCII);
		int row = (width * 3 + 3) & ~3;
		int pixels = row * height;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, new byte[] {'B', 'M'}, le(14 + 124 + pixels + path.length,
				4), le(0, 4), le(14 + 124, 4));
		write(out, le(124, 4), le(width, 4), le(height, 4), le(1, 2),
				le(24, 2), le(0, 4), le(pixels, 4), le(2835, 4), le(2835, 4),
				le(0, 4), le(0, 4), new byte[16], le(0x4C494E4BL, 4),
				new byte[36], new byte[12], le(4, 4), le(124 + pixels, 4),
				le(path.length, 4), le(0, 4));
		for (int y = 0; y < height; y++) {
			byte[] r = new byte[row];
			for (int x = 0; x < width; x++) {
				r[x * 3] = (byte) (x * 4);
				r[x * 3 + 1] = (byte) (y * 6);
				r[x * 3 + 2] = (byte) 200;
			}
			write(out, r);
		}
		write(out, path);
		return out.toByteArray();
	}

	private static byte[] largeJpeg() {
		Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
		b.eraseColor(Color.rgb(30, 140, 90));
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		assertTrue(b.compress(Bitmap.CompressFormat.JPEG, 90, jpeg));
		byte[] small = jpeg.toByteArray();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(small, 0, 2);
		byte[] comment = new byte[65533];
		System.arraycopy(MARKER, 0, comment, 10, MARKER.length);
		while (out.size() < 17 * 1024 * 1024) {
			write(out, new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFF,
					(byte) 0xFF}, comment);
		}
		out.write(small, 2, small.length - 2);
		return out.toByteArray();
	}

	private static Bitmap decode(byte[] image) {
		Bitmap b = BitmapFactory.decodeByteArray(image, 0, image.length);
		assertNotNull("the sent image does not decode", b);
		return b;
	}

	@Test
	public void aWindowsBitmapIsSentAsAPng() throws Exception {
		byte[] in = bmp(40, 30);
		assertNotNull("the device does not read the bitmap", decode(in));
		SharedMediaSanitizer.Cleaned c = share(in, "image/bmp");
		assertEquals("image/png", c.getMimeType());
		assertFalse(DeviceMediaFiles.contains(c.getData(), MARKER));
		Bitmap out = decode(c.getData());
		assertEquals(40, out.getWidth());
		assertEquals(30, out.getHeight());
	}

	@Test
	public void anAvifImageIsSentAsAJpegOrRefusedWithAReason()
			throws Exception {
		byte[] in = Base64.decode(AVIF, Base64.DEFAULT);
		assertTrue(DeviceMediaFiles.contains(in, MARKER));
		SharedMediaSanitizer.Cleaned c;
		try {
			c = share(in, "image/avif");
		} catch (MediaRefusedException e) {
			assertTrue("AVIF refused on Android " + Build.VERSION.SDK_INT,
					Build.VERSION.SDK_INT < 31);
			return;
		}
		assertEquals("image/jpeg", c.getMimeType());
		assertFalse(DeviceMediaFiles.contains(c.getData(), MARKER));
		Bitmap out = decode(c.getData());
		assertEquals(32, out.getWidth());
		assertEquals(24, out.getHeight());
	}

	@Test
	public void aPhotoFileLargerThan16MbIsSent() throws Exception {
		byte[] in = largeJpeg();
		assertTrue(in.length > 16 * 1024 * 1024);
		assertNotNull("the device does not read the photo", decode(in));
		SharedMediaSanitizer.Cleaned c = share(in, "image/jpeg");
		assertEquals("image/jpeg", c.getMimeType());
		assertFalse(DeviceMediaFiles.contains(c.getData(), MARKER));
		assertTrue(c.getData().length < 1024 * 1024);
		assertEquals(64, decode(c.getData()).getWidth());
	}

	@Test
	public void anAnimatedGifIsStillPlayedAsAnAnimation() throws Exception {
		byte[] in = Base64.decode(ANIMATED_GIF, Base64.DEFAULT);
		SharedMediaSanitizer.Cleaned c = share(in, "image/gif");
		assertEquals("image/gif", c.getMimeType());
		assertFalse(DeviceMediaFiles.contains(c.getData(), MARKER));
		Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(
				ByteBuffer.wrap(c.getData())));
		assertTrue("the sent GIF is no longer animated: " + d,
				d instanceof AnimatedImageDrawable);
		assertEquals(24, d.getIntrinsicWidth());
		assertEquals(16, d.getIntrinsicHeight());
	}

	@Test
	public void formatsThatStayRefusedSayWhy() throws Exception {
		byte[] tiff = new byte[4096];
		tiff[0] = 'I';
		tiff[1] = 'I';
		tiff[2] = 42;
		System.arraycopy(MARKER, 0, tiff, 100, MARKER.length);
		try {
			share(tiff, "image/tiff");
			fail("TIFF was sent");
		} catch (MediaRefusedException expected) {
			assertFalse(expected.isTooLarge());
		}
	}
}
