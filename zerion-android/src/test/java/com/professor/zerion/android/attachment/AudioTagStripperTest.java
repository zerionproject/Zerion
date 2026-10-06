package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.mpegFrames;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.samples;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class AudioTagStripperTest {

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] le32(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	private static byte[] id3v24WithFooter(byte[] body) {
		int n = body.length;
		byte[] size = {0, 0, (byte) ((n >> 7) & 0x7F), (byte) (n & 0x7F)};
		return concat(ascii("ID3"), new byte[] {4, 0, 0x10}, size, body,
				ascii("3DI"), new byte[] {4, 0, 0x10}, size);
	}

	private static byte[] apeTag(byte[] item, boolean header) {
		long size = 32 + item.length;
		byte[] footer = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(header ? 0x80000000L : 0), new byte[8]);
		if (!header) return concat(item, footer);
		byte[] top = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(0xA0000000L), new byte[8]);
		return concat(top, item, footer);
	}

	private static void assertRefused(byte[] d) {
		try {
			AudioTagStripper.withoutTags(d);
			fail("malformed tag accepted");
		} catch (IOException expected) {
		}
	}

	@Test
	public void everyTagLayoutAroundAnMpegStreamIsRemoved() throws Exception {
		byte[] frames = mpegFrames(3);
		byte[] tagged = concat(id3v23(ascii("first")),
				id3v24WithFooter(ascii("second-tag-body")), frames,
				apeTag(ascii("Artist\0Someone"), true),
				concat(ascii("TAG+"), new byte[223]), id3v1(ascii("title")));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(tagged));
		byte[] appended = concat(frames, id3v24WithFooter(ascii("late")));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(appended));
		byte[] apeOnly = concat(frames, apeTag(ascii("Year\0" + "2026"),
				false));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(apeOnly));
	}

	@Test
	public void audioWithoutTagsIsReturnedAsItIs() throws Exception {
		byte[] frames = mpegFrames(2);
		assertSame(frames, AudioTagStripper.withoutTags(frames));
		byte[] other = samples(300);
		assertSame(other, AudioTagStripper.withoutTags(other));
	}

	@Test
	public void malformedTagsAreRefused() {
		assertRefused(ascii("ID3"));
		assertRefused(concat(ascii("ID3"), new byte[] {5, 0, 0, 0, 0, 0, 1},
				mpegFrames(1)));
		assertRefused(concat(ascii("ID3"), new byte[] {3, (byte) 0xFF, 0, 0,
				0, 0, 1}, mpegFrames(1)));
		assertRefused(concat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0, 0x7F,
				0x7F}, mpegFrames(1)));
		byte[] badApe = concat(mpegFrames(1), ascii("APETAGEX"), le32(2000),
				le32(4), le32(0), le32(0), new byte[8]);
		assertRefused(badApe);
		byte[] longApe = concat(mpegFrames(1), ascii("APETAGEX"), le32(2000),
				le32(1 << 20), le32(0), le32(0), new byte[8]);
		assertRefused(longApe);
	}

	private static byte[] chunk(String id, byte[] body) {
		byte[] c = concat(ascii(id), le32(body.length), body);
		return (body.length & 1) != 0 ? concat(c, new byte[1]) : c;
	}

	private static byte[] riff(byte[]... chunks) {
		byte[] body = concat(chunks);
		return concat(ascii("RIFF"), le32(body.length + 4L), ascii("WAVE"),
				body);
	}

	@Test
	public void aWaveFileKeepsFormatFactAndSamplesInOrder() throws Exception {
		byte[] fmt = samples(16);
		byte[] fact = le32(1234);
		byte[] data = samples(333);
		byte[] wave = riff(chunk("JUNK", samples(28)), chunk("fmt ", fmt),
				chunk("fact", fact), chunk("LIST", ascii("INFOIART")),
				chunk("data", data), chunk("id3 ", id3v23(ascii("x"))),
				chunk("_PMX", ascii("<x:xmpmeta/>")));
		byte[] trailing = concat(wave, ascii("appended after the file"));
		byte[] expected = riff(chunk("fmt ", fmt), chunk("fact", fact),
				chunk("data", data));
		assertArrayEquals(expected, AudioTagStripper.waveWithoutMetadata(wave));
		assertArrayEquals(expected,
				AudioTagStripper.waveWithoutMetadata(trailing));
	}

	@Test
	public void aStreamedWaveWithAnOpenSampleChunkKeepsItsSamples()
			throws Exception {
		byte[] fmt = samples(16);
		byte[] data = samples(400);
		byte[] streamed = concat(ascii("RIFF"), le32(0xFFFFFFFFL),
				ascii("WAVE"), chunk("fmt ", fmt), ascii("data"),
				le32(0xFFFFFFFFL), data);
		assertArrayEquals(riff(chunk("fmt ", fmt), chunk("data", data)),
				AudioTagStripper.waveWithoutMetadata(streamed));
	}

	@Test
	public void anIncompleteOrBrokenWaveIsRefused() {
		byte[][] broken = {
				riff(chunk("LIST", ascii("INFO")), chunk("data", samples(8))),
				riff(chunk("fmt ", samples(16))),
				riff(chunk("fmt ", samples(16)), ascii("LIST"),
						le32(1 << 20), samples(10)),
				ascii("RIFF\0\0\0\0AVI LIST")};
		for (byte[] b : broken) {
			try {
				AudioTagStripper.waveWithoutMetadata(b);
				fail("broken wave accepted");
			} catch (IOException expected) {
			}
		}
	}
}
