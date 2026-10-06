package com.professor.zerion.android.vault.utils;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Mp4MetadataScrubberTest {

	private static final String VERSION = "14";
	private static final String MAKER = "ZtPlantedMaker";
	private static final String MODEL = "ZtPlantedModel g99";

	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] u32(long v) {
		return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8),
				(byte) v};
	}

	static byte[] box(String type, byte[]... body) {
		byte[] b = concat(body);
		return concat(u32(b.length + 8), ascii(type), b);
	}

	private static byte[] largeBox(String type, byte[]... body) {
		byte[] b = concat(body);
		long size = b.length + 16L;
		return concat(u32(1), ascii(type), u32(size >>> 32), u32(size), b);
	}

	static byte[] deviceMeta(boolean makerAndModel) {
		List<String> keys = new ArrayList<>();
		List<String> values = new ArrayList<>();
		keys.add("com.android.version");
		values.add(VERSION);
		if (makerAndModel) {
			keys.add("com.android.manufacturer");
			values.add(MAKER);
			keys.add("com.android.model");
			values.add(MODEL);
		}
		ByteArrayOutputStream keyList = new ByteArrayOutputStream();
		ByteArrayOutputStream items = new ByteArrayOutputStream();
		for (int i = 0; i < keys.size(); i++) {
			byte[] k = ascii(keys.get(i));
			byte[] entry = concat(u32(k.length + 8), ascii("mdta"), k);
			keyList.write(entry, 0, entry.length);
			byte[] data = box("data", u32(1), u32(0), ascii(values.get(i)));
			byte[] item = concat(u32(data.length + 8), u32(i + 1), data);
			items.write(item, 0, item.length);
		}
		byte[] hdlr = box("hdlr", u32(0), u32(0), ascii("mdta"), u32(0),
				u32(0), u32(0), new byte[1]);
		byte[] keysBox = box("keys", u32(0), u32(keys.size()),
				keyList.toByteArray());
		byte[] ilst = box("ilst", items.toByteArray());
		return box("meta", hdlr, keysBox, ilst);
	}

	private static byte[] ftyp() {
		return box("ftyp", ascii("mp42"), u32(0), ascii("isommp42"));
	}

	private static byte[] mvhd() {
		byte[] b = new byte[100];
		b[19] = (byte) 0xE8;
		b[18] = 0x03;
		return box("mvhd", b);
	}

	private static byte[] trak(long offset, int sampleLength,
			byte[]... extra) {
		byte[] stco = box("stco", u32(0), u32(1), u32(offset));
		byte[] stsz = box("stsz", u32(0), u32(0), u32(1), u32(sampleLength));
		byte[] stsc = box("stsc", u32(0), u32(1), u32(1), u32(1), u32(1));
		byte[] stts = box("stts", u32(0), u32(1), u32(1), u32(1000));
		byte[] stsd = box("stsd", u32(0), u32(1), box("avc1", new byte[78]));
		byte[] stbl = box("stbl", stsd, stts, stsc, stsz, stco);
		byte[] minf = box("minf", box("vmhd", new byte[12]), stbl);
		byte[] hdlr = box("hdlr", u32(0), u32(0), ascii("vide"), u32(0),
				u32(0), u32(0), ascii("VideoHandle\0"));
		byte[] mdia = box("mdia", box("mdhd", new byte[24]), hdlr, minf);
		return box("trak", concat(box("tkhd", new byte[84]), concat(extra),
				mdia));
	}

	private static byte[] sample() {
		byte[] s = new byte[3000];
		for (int i = 0; i < s.length; i++) s[i] = (byte) (i * 13 + 7);
		return s;
	}

	private File write(byte[] mp4) throws IOException {
		File f = File.createTempFile("zt_mp4_", ".mp4");
		temp.add(f);
		Files.write(f.toPath(), mp4);
		return f;
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		return indexOf(hay, needle) >= 0;
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	private static long readU32(byte[] d, int off) {
		return ((d[off] & 0xFFL) << 24) | ((d[off + 1] & 0xFFL) << 16)
				| ((d[off + 2] & 0xFFL) << 8) | (d[off + 3] & 0xFFL);
	}

	private static List<String> types(byte[] d, int from, int to) {
		List<String> out = new ArrayList<>();
		int pos = from;
		while (pos < to) {
			long size = readU32(d, pos);
			int header = 8;
			if (size == 1) {
				size = (readU32(d, pos + 8) << 32) | readU32(d, pos + 12);
				header = 16;
			}
			assertTrue("box size at " + pos, size >= header
					&& pos + size <= to);
			out.add(new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1));
			pos += (int) size;
		}
		assertEquals(to, pos);
		return out;
	}

	private static int boxStart(byte[] d, String type) {
		return indexOf(d, ascii(type)) - 4;
	}

	private static void assertNoDeviceDetails(byte[] out) {
		for (String s : new String[] {"com.android", MAKER, MODEL, "mdta",
				"ilst"}) {
			assertFalse(s + " is still in the file", contains(out, ascii(s)));
		}
	}

	private static void assertSameLayout(byte[] in, byte[] out,
			int sampleOffset) {
		assertEquals("the file length changed", in.length, out.length);
		assertArrayEquals("the sample moved or changed", sample(),
				Arrays.copyOfRange(out, sampleOffset,
						sampleOffset + sample().length));
		assertEquals(types(in, 0, in.length), types(out, 0, out.length));
	}

	@Test
	public void theDeviceKeysOfAMovieAtTheFrontAreRemoved() throws Exception {
		byte[] meta = deviceMeta(true);
		byte[] head = concat(ftyp());
		int moovLength = box("moov", mvhd(), meta, trak(0, 3000)).length;
		long mdatData = head.length + moovLength + 64 + 8;
		byte[] moov = box("moov", mvhd(), meta, trak(mdatData, 3000));
		byte[] in = concat(head, moov, box("free", new byte[56]),
				box("mdat", sample()));
		assertTrue(contains(in, ascii("com.android.version")));
		assertTrue(contains(in, ascii(MODEL)));
		File f = write(in);
		Mp4MetadataScrubber.scrub(f);
		byte[] out = Files.readAllBytes(f.toPath());
		assertNoDeviceDetails(out);
		assertSameLayout(in, out, (int) mdatData);
		int moovStart = head.length;
		assertEquals(Arrays.asList("mvhd", "free", "trak"),
				types(out, moovStart + 8, moovStart + moov.length));
		int free = moovStart + 8 + mvhd().length;
		for (int i = free + 8; i < free + meta.length; i++) {
			assertEquals("free space not zeroed at " + i, 0, out[i]);
		}
		assertEquals(readU32(in, boxStart(in, "stco") + 16),
				readU32(out, boxStart(out, "stco") + 16));
	}

	@Test
	public void theDeviceKeysOfAMovieAtTheEndAreRemoved() throws Exception {
		byte[] head = concat(ftyp(), box("free", new byte[200]));
		long mdatData = head.length + 8;
		byte[] in = concat(head, box("mdat", sample()),
				box("moov", mvhd(), trak(mdatData, 3000), deviceMeta(false)));
		File f = write(in);
		Mp4MetadataScrubber.scrub(f);
		byte[] out = Files.readAllBytes(f.toPath());
		assertNoDeviceDetails(out);
		assertSameLayout(in, out, (int) mdatData);
	}

	@Test
	public void userDataOfTheMovieAndOfATrackIsRemoved() throws Exception {
		byte[] location = box("©xyz", u32(0x0012_15c7),
				ascii("+52.3702+004.8952/"));
		byte[] udta = box("udta", location, box("©mak", ascii(MAKER)));
		byte[] trackUdta = box("udta", box("name", ascii(MODEL)));
		long mdatData = ftyp().length + 8;
		byte[] in = concat(ftyp(), box("mdat", sample()),
				box("moov", mvhd(), udta, trak(mdatData, 3000, trackUdta),
						deviceMeta(true)));
		File f = write(in);
		Mp4MetadataScrubber.scrub(f);
		byte[] out = Files.readAllBytes(f.toPath());
		assertNoDeviceDetails(out);
		assertFalse(contains(out, ascii("+52.3702")));
		assertFalse(contains(out, ascii("xyz")));
		assertSameLayout(in, out, (int) mdatData);
	}

	@Test
	public void largeSizeBoxesAreFollowed() throws Exception {
		long mdatData = ftyp().length + 16;
		byte[] in = concat(ftyp(), largeBox("mdat", sample()),
				box("moov", mvhd(), trak(mdatData, 3000),
						largeBox("meta", deviceMeta(true))));
		File f = write(in);
		Mp4MetadataScrubber.scrub(f);
		byte[] out = Files.readAllBytes(f.toPath());
		assertNoDeviceDetails(out);
		assertSameLayout(in, out, (int) mdatData);
	}

	@Test
	public void aFileWithoutMetadataIsLeftAsItIs() throws Exception {
		long mdatData = ftyp().length + 8;
		byte[] in = concat(ftyp(), box("mdat", sample()),
				box("moov", mvhd(), trak(mdatData, 3000)));
		File f = write(in);
		Mp4MetadataScrubber.scrub(f);
		assertArrayEquals(in, Files.readAllBytes(f.toPath()));
	}

	@Test
	public void aFileThatCannotBeFollowedIsRefused() throws Exception {
		long mdatData = ftyp().length + 8;
		byte[] good = concat(ftyp(), box("mdat", sample()),
				box("moov", mvhd(), trak(mdatData, 3000), deviceMeta(true)));
		byte[] cut = Arrays.copyOf(good, good.length - 10);
		byte[] noMovie = concat(ftyp(), box("mdat", sample()));
		byte[] badChild = concat(ftyp(), box("moov", u32(4), ascii("mvhd"),
				new byte[100]));
		byte[] keyElsewhere = concat(ftyp(), box("mdat", sample()),
				box("moov", mvhd(), trak(mdatData, 3000),
						box("uuid", new byte[16], ascii("com.android.model"),
								ascii(MODEL))));
		Object[][] cases = {{"a file cut short", cut},
				{"a file without a movie", noMovie},
				{"a box smaller than its header", badChild},
				{"a device key outside the metadata boxes", keyElsewhere}};
		for (Object[] c : cases) {
			File f = write((byte[]) c[1]);
			try {
				Mp4MetadataScrubber.scrub(f);
				fail(c[0] + " was accepted");
			} catch (IOException expected) {
			}
		}
	}
}
