package com.professor.zerion.android.attachment;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GifMetadataStripTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 50L * 1024 * 1024;
	private static final byte[] MARKER = "ZtPlantedGps52.3702N4.8952E"
			.getBytes(StandardCharsets.US_ASCII);

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

	private Context ctx;
	private final List<File> temp = new ArrayList<>();

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	static byte[] animatedGif() {
		return Base64.decode(ANIMATED_GIF, Base64.DEFAULT);
	}

	private Uri stage(byte[] data) throws IOException {
		File f = File.createTempFile("zt_gif_", ".gif", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), data);
		return Uri.fromFile(f);
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	static final class Blocks {
		byte[] screen;
		final List<byte[]> frames = new ArrayList<>();
		final List<String> applications = new ArrayList<>();
		final List<byte[]> applicationData = new ArrayList<>();
		int comments;
		int trailerAt = -1;
	}

	private static int subBlocksEnd(byte[] d, int pos) {
		while (d[pos] != 0) pos += (d[pos] & 0xFF) + 1;
		return pos + 1;
	}

	static Blocks read(byte[] d) {
		Blocks b = new Blocks();
		int packed = d[10] & 0xFF;
		int pos = 13 + ((packed & 0x80) != 0 ? 3 << ((packed & 7) + 1) : 0);
		b.screen = Arrays.copyOfRange(d, 6, pos);
		byte[] control = new byte[0];
		while (true) {
			int block = d[pos] & 0xFF;
			if (block == 0x3B) {
				b.trailerAt = pos;
				return b;
			}
			if (block == 0x21) {
				int label = d[pos + 1] & 0xFF;
				int end = subBlocksEnd(d, pos + 2);
				if (label == 0xF9) {
					control = Arrays.copyOfRange(d, pos, end);
				} else if (label == 0xFF) {
					b.applications.add(new String(d, pos + 3, 11,
							StandardCharsets.ISO_8859_1));
					b.applicationData.add(Arrays.copyOfRange(d, pos + 14,
							end));
				} else if (label == 0xFE) {
					b.comments++;
				}
				pos = end;
			} else if (block == 0x2C) {
				int imagePacked = d[pos + 9] & 0xFF;
				int data = pos + 10 + ((imagePacked & 0x80) != 0
						? 3 << ((imagePacked & 7) + 1) : 0);
				int end = subBlocksEnd(d, data + 1);
				ByteArrayOutputStream f = new ByteArrayOutputStream();
				f.write(control, 0, control.length);
				f.write(d, pos, end - pos);
				b.frames.add(f.toByteArray());
				control = new byte[0];
				pos = end;
			} else {
				throw new AssertionError("unexpected block " + block);
			}
		}
	}

	private SharedMediaSanitizer.Cleaned share(byte[] gif, String declared)
			throws IOException {
		return new SharedMediaSanitizer(ctx).sanitize(stage(gif), declared,
				gif, MAX);
	}

	@Test
	public void anAnimatedGifStaysAnimatedWithoutItsMetadata()
			throws Exception {
		byte[] gif = animatedGif();
		Blocks before = read(gif);
		assertEquals(4, before.frames.size());
		assertEquals(1, before.comments);
		assertTrue(before.applications.contains("XMP DataXMP"));
		assertTrue(before.applications.contains("ICCRGBG1012"));
		assertTrue(before.trailerAt < gif.length - 1);
		assertTrue(contains(gif, MARKER));
		for (String declared : new String[] {"image/gif",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c = share(gif, declared);
			assertEquals(declared, "image/gif", c.getMimeType());
			byte[] out = c.getData();
			assertFalse("the comment, XMP, profile or trailing bytes were sent",
					contains(out, MARKER));
			Blocks after = read(out);
			assertEquals("GIF89a", new String(out, 0, 6,
					StandardCharsets.US_ASCII));
			assertArrayEquals("screen and colours", before.screen,
					after.screen);
			assertEquals("frames", before.frames.size(), after.frames.size());
			for (int i = 0; i < before.frames.size(); i++) {
				assertArrayEquals("frame " + i, before.frames.get(i),
						after.frames.get(i));
			}
			assertEquals(0, after.comments);
			assertEquals(Arrays.asList("NETSCAPE2.0"), after.applications);
			assertArrayEquals("loop count", new byte[] {3, 1, 3, 0, 0},
					after.applicationData.get(0));
			assertEquals("bytes after the end", out.length - 1,
					after.trailerAt);
		}
	}

	@Test
	public void plainTextBlocksAndTheirTimingAreLeftOut() throws Exception {
		byte[] gif = animatedGif();
		Blocks before = read(gif);
		int packed = gif[10] & 0xFF;
		int screenEnd = 13 + ((packed & 0x80) != 0
				? 3 << ((packed & 7) + 1) : 0);
		ByteArrayOutputStream in = new ByteArrayOutputStream();
		in.write(gif, 0, screenEnd);
		byte[] text = {0x21, (byte) 0xF9, 4, 0, 50, 0, 0, 0, 0x21, 0x01, 12,
				0, 0, 0, 0, 10, 0, 10, 0, 8, 8, 1, 0};
		in.write(text, 0, text.length);
		in.write(MARKER.length);
		in.write(MARKER, 0, MARKER.length);
		in.write(0);
		in.write(gif, screenEnd, gif.length - screenEnd);
		byte[] out = GifMetadataStripper.withoutMetadata(in.toByteArray());
		assertFalse(contains(out, MARKER));
		Blocks after = read(out);
		assertEquals(before.frames.size(), after.frames.size());
		for (int i = 0; i < before.frames.size(); i++) {
			assertArrayEquals("frame " + i, before.frames.get(i),
					after.frames.get(i));
		}
	}

	@Test
	public void aGifWhoseBlocksCannotBeFollowedIsNotSentAsAGif()
			throws Exception {
		byte[] gif = animatedGif();
		int trailer = read(gif).trailerAt;
		byte[] cut = Arrays.copyOf(gif, trailer - 20);
		byte[] junk = gif.clone();
		junk[trailer] = 0x55;
		for (byte[] bad : new byte[][] {cut, junk}) {
			try {
				GifMetadataStripper.withoutMetadata(bad);
				fail("a malformed GIF was rebuilt");
			} catch (IOException expected) {
			}
			SharedMediaSanitizer.Cleaned c;
			try {
				c = share(bad, "image/gif");
			} catch (IOException refused) {
				continue;
			}
			assertNotEquals("image/gif", c.getMimeType());
			assertFalse(contains(c.getData(), MARKER));
		}
	}
}
