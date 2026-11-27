package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExternalViewerArchiveTest {

	private static final String ZIP = "application/zip";
	private static final String DOCX = "application/vnd.openxmlformats-"
			+ "officedocument.wordprocessingml.document";
	private static final String XLSX = "application/vnd.openxmlformats-"
			+ "officedocument.spreadsheetml.sheet";
	private static final String PPTX = "application/vnd.openxmlformats-"
			+ "officedocument.presentationml.presentation";
	private static final String ODT =
			"application/vnd.oasis.opendocument.text";
	private static final int TAIL = 65_557;
	private static final long NOT_STATED = 0xFFFFFFFFL;

	private static final class CountingSource
			implements ExternalViewerTypes.Source {

		private final ExternalViewerTypes.Source source;
		private long bytesRead;
		private int reads;

		CountingSource(byte[] content) {
			source = new ExternalViewerTypes.ArraySource(content);
		}

		@Override
		public long length() throws IOException {
			return source.length();
		}

		@Override
		public byte[] read(long pos, int n) throws IOException {
			byte[] read = source.read(pos, n);
			bytesRead += read.length;
			reads++;
			return read;
		}
	}

	private static byte[] entry(String name, int extra, int comment) {
		byte[] n = name.getBytes(StandardCharsets.UTF_8);
		byte[] e = new byte[46 + n.length + extra + comment];
		e[0] = 'P';
		e[1] = 'K';
		e[2] = 1;
		e[3] = 2;
		e[28] = (byte) n.length;
		e[29] = (byte) (n.length >>> 8);
		e[30] = (byte) extra;
		e[31] = (byte) (extra >>> 8);
		e[32] = (byte) comment;
		e[33] = (byte) (comment >>> 8);
		System.arraycopy(n, 0, e, 46, n.length);
		return e;
	}

	private static byte[] entry(String name) {
		return entry(name, 0, 0);
	}

	private static byte[] endRecord(int entries, long size, long offset) {
		byte[] r = new byte[22];
		r[0] = 'P';
		r[1] = 'K';
		r[2] = 5;
		r[3] = 6;
		r[8] = (byte) entries;
		r[9] = (byte) (entries >>> 8);
		r[10] = (byte) entries;
		r[11] = (byte) (entries >>> 8);
		for (int i = 0; i < 4; i++) {
			r[12 + i] = (byte) (size >>> (8 * i));
			r[16 + i] = (byte) (offset >>> (8 * i));
		}
		return r;
	}

	private static byte[] largeArchiveLocator() {
		byte[] l = new byte[20];
		l[0] = 'P';
		l[1] = 'K';
		l[2] = 6;
		l[3] = 7;
		return l;
	}

	private static byte[] filler(int length) {
		byte[] f = new byte[length];
		Arrays.fill(f, (byte) 0x55);
		return f;
	}

	private static byte[] join(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] paddingEntries(int count) {
		byte[][] entries = new byte[count][];
		for (int i = 0; i < count; i++) {
			entries[i] = entry(String.format("pad%05d", i), 0, 65_535);
		}
		return join(entries);
	}

	private static byte[] zip(String... names) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(out)) {
			for (String name : names) {
				z.putNextEntry(new ZipEntry(name));
				z.write(("content of " + name)
						.getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] zipHoldingAsIs(String name, byte[] member)
			throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(out)) {
			z.putNextEntry(new ZipEntry("readme.txt"));
			z.write("read me".getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
			ZipEntry asIs = new ZipEntry(name);
			CRC32 crc = new CRC32();
			crc.update(member);
			asIs.setMethod(ZipEntry.STORED);
			asIs.setSize(member.length);
			asIs.setCompressedSize(member.length);
			asIs.setCrc(crc.getValue());
			z.putNextEntry(asIs);
			z.write(member);
			z.closeEntry();
		}
		return out.toByteArray();
	}

	private static String opened(String declared, byte[] content) {
		return ExternalViewerTypes.typeForContent(declared, content);
	}

	private static void assertRefused(String what, byte[] content) {
		assertNull(what, opened(ZIP, content));
		assertNull(what, opened(ExternalViewerTypes.OPAQUE, content));
		assertNull(what, opened(DOCX, content));
		assertNull(what, opened("text/plain", content));
	}

	@Test
	public void aManifestBehindMoreEntriesThanAreReadIsRefused() {
		byte[] padding = paddingEntries(129);
		assertTrue(padding.length > ExternalViewerTypes.MAX_DIRECTORY_BYTES);
		byte[] directory = join(padding, entry("AndroidManifest.xml"));
		assertRefused("manifest after the cap", join(directory,
				endRecord(130, directory.length, 0)));
		assertRefused("a directory too long to walk", join(padding,
				endRecord(129, padding.length, 0)));
	}

	@Test
	public void aLongDirectoryIsWalkedToItsEnd() {
		byte[] padding = paddingEntries(60);
		assertTrue(padding.length > 3 * 1024 * 1024);
		assertEquals(ZIP, opened(ZIP, join(padding,
				endRecord(60, padding.length, 0))));
		byte[] directory = join(padding, entry("AndroidManifest.xml"));
		assertRefused("manifest as the last of many", join(directory,
				endRecord(61, directory.length, 0)));
	}

	@Test
	public void aDirectoryAwayFromItsEndRecordIsStillRead() {
		byte[] directory = entry("AndroidManifest.xml");
		assertRefused("a gap before the end record", join(directory,
				new byte[64], endRecord(1, directory.length, 0)));
		assertRefused("a gap before the end record, after other content",
				join(filler(100), directory, new byte[64],
						endRecord(1, directory.length, 100)));
		byte[] decoy = entry("readme.txt");
		assertRefused("a harmless directory right before the end record",
				join(directory, decoy, endRecord(1, decoy.length, 0)));
		assertRefused("a harmless directory where the record points",
				join(decoy, directory,
						endRecord(1, directory.length, 0)));
	}

	@Test
	public void anArchiveInTheLargeFormatIsRefused() {
		byte[] directory = entry("readme.txt");
		assertEquals(ZIP, opened(ZIP, join(directory,
				endRecord(1, directory.length, 0))));
		assertRefused("size not stated", join(directory,
				endRecord(1, NOT_STATED, 0)));
		assertRefused("offset not stated", join(directory,
				endRecord(1, directory.length, NOT_STATED)));
		assertRefused("count not stated", join(directory,
				endRecord(0xFFFF, directory.length, 0)));
		assertRefused("large format locator", join(directory,
				largeArchiveLocator(), endRecord(1, directory.length, 0)));
		byte[] program = entry("AndroidManifest.xml");
		assertRefused("manifest with the size not stated", join(program,
				endRecord(1, NOT_STATED, 0)));
		assertRefused("manifest behind a large format locator",
				join(program, largeArchiveLocator(),
						endRecord(1, NOT_STATED, NOT_STATED)));
	}

	@Test
	public void aDirectoryThatEndsBeforeWhatItsRecordStatesIsRefused() {
		byte[] two = join(entry("a.txt"), entry("b.txt"));
		assertEquals(ZIP, opened(ZIP, join(two,
				endRecord(2, two.length, 0))));
		assertRefused("fewer entries than stated", join(two,
				endRecord(5, two.length, 0)));
		assertRefused("shorter than stated", join(two, new byte[30],
				endRecord(2, two.length + 30, 0)));
		byte[] cut = Arrays.copyOf(two, two.length - 3);
		assertRefused("a name cut off", join(cut,
				endRecord(2, cut.length, 0)));
	}

	@Test
	public void anArchiveThatCarriesAPackageIsRefused() throws Exception {
		for (String name : new String[] {"base.apk", "bundle/base.apk",
				"splits/config.EN.APK", "x.Apk", ".apk", "base.apks",
				"app.APKS", "game.xapk", "inner/App.XApk", "app.apkm",
				"a.ApKm", ".xapk"}) {
			assertRefused(name, zip("icon.png", name, "manifest.json"));
			assertRefused(name, join(entry(name),
					endRecord(1, 46 + name.length(), 0)));
		}
		for (String name : new String[] {"notes.apk.txt", "apk", "my-apk",
				"apk/readme.txt", "base_apk", "base.apkx", "base.apksx",
				"xapk", "notes.xapk.md", "base_apkm", "apkm/readme.txt",
				"a.apkz"}) {
			assertEquals(name, ZIP, opened(ZIP, zip("icon.png", name)));
		}
	}

	@Test
	public void anArchiveThatCarriesProgramCodeIsRefused() throws Exception {
		assertRefused("classes.dex", zip("META-INF/MANIFEST.MF",
				"classes.dex"));
		assertRefused("classes.dex alone", zip("classes.dex"));
		for (String name : new String[] {"classes.dex.txt",
				"build/classes.dex", "classes_dex", "Classes.Dex.md"}) {
			assertEquals(name, ZIP, opened(ZIP, zip("readme.md", name)));
		}
	}

	@Test
	public void manyEndRecordsThatLeadNowhereCostAlmostNothing()
			throws Exception {
		int most = ExternalViewerTypes.MAX_END_RECORDS;
		for (int records : new int[] {most, most + 1, 2_500}) {
			byte[][] parts = new byte[records + 1][];
			parts[0] = filler(1024 * 1024);
			for (int i = 0; i < records; i++) {
				parts[i + 1] = endRecord(1, 1024 * 1024, i * 256L);
			}
			byte[] content = join(parts);
			CountingSource counted = new CountingSource(content);
			boolean refused = records > most;
			assertEquals(String.valueOf(records), refused,
					ExternalViewerTypes.isProgram(counted));
			long examined = Math.min(records, most);
			assertTrue("read " + counted.bytesRead,
					counted.bytesRead <= 8 + TAIL + examined * 12L);
			assertTrue("reads " + counted.reads,
					counted.reads <= 2 + examined * 3L);
			assertEquals(String.valueOf(records), refused ? null : ZIP,
					opened(ZIP, content));
		}
	}

	@Test
	public void manyEndRecordsThatLeadToOneDirectoryAreRefusedCheaply()
			throws Exception {
		byte[] directory = entry("readme.txt");
		int most = ExternalViewerTypes.MAX_WALKED_DIRECTORIES;
		for (int records : new int[] {most, most + 1, 2_000}) {
			byte[][] parts = new byte[records + 2][];
			parts[0] = directory;
			parts[1] = filler(200_000);
			for (int i = 0; i < records; i++) {
				parts[i + 2] = endRecord(1, directory.length, 0);
			}
			CountingSource counted = new CountingSource(join(parts));
			assertEquals(String.valueOf(records), records > most,
					ExternalViewerTypes.isProgram(counted));
			assertTrue("read " + counted.bytesRead, counted.bytesRead
					<= 8 + TAIL + 64 * 1024 + records * 12L);
		}
	}

	@Test
	public void noMoreThanTheCapIsEverReadFromADirectory() throws Exception {
		byte[] padding = paddingEntries(140);
		CountingSource counted = new CountingSource(join(padding,
				endRecord(140, padding.length, 0)));
		assertTrue(ExternalViewerTypes.isProgram(counted));
		assertTrue("read " + counted.bytesRead, counted.bytesRead
				<= 8 + TAIL + ExternalViewerTypes.MAX_DIRECTORY_BYTES);
		assertTrue("reads " + counted.reads, counted.reads <= 400);
	}

	@Test
	public void ordinaryOfficeFilesAndArchivesStillOpen() throws Exception {
		assertEquals(DOCX, opened(DOCX, zip("[Content_Types].xml",
				"_rels/.rels", "word/document.xml", "word/styles.xml",
				"word/media/image1.png", "word/embeddings/oleObject1.bin",
				"docProps/core.xml", "docProps/app.xml")));
		assertEquals(XLSX, opened(XLSX, zip("[Content_Types].xml",
				"_rels/.rels", "xl/workbook.xml", "xl/sharedStrings.xml",
				"xl/worksheets/sheet1.xml", "docProps/core.xml")));
		assertEquals(PPTX, opened(PPTX, zip("[Content_Types].xml",
				"_rels/.rels", "ppt/presentation.xml",
				"ppt/slides/slide1.xml", "ppt/media/video1.mp4")));
		assertEquals(ODT, opened(ODT, zip("mimetype", "content.xml",
				"styles.xml", "meta.xml", "META-INF/manifest.xml",
				"Thumbnails/thumbnail.png")));
		assertEquals(ExternalViewerTypes.TEXT, opened("application/epub+zip",
				zip("mimetype", "META-INF/container.xml",
						"OEBPS/content.opf", "OEBPS/chapter1.xhtml")));
		assertEquals(ZIP, opened(ZIP, zip("readme.md", "src/Main.java",
				"lib/helper.jar", "docs/AndroidManifest.xml")));
		assertEquals(ZIP, opened(ZIP, endRecord(0, 0, 0)));
		byte[] plain = zip("a.txt", "b.txt");
		assertEquals(ZIP, opened(ZIP, join(filler(700), plain)));
		assertEquals(ZIP, opened(ZIP, join(filler(70_000), plain)));
	}

	@Test
	public void anArchiveWithThousandsOfFilesStillOpens() throws Exception {
		String[] names = new String[3_000];
		for (int i = 0; i < names.length; i++) {
			names[i] = "photos/2026/holiday/picture-" + i + ".jpg";
		}
		byte[] album = zip(names);
		CountingSource counted = new CountingSource(album);
		assertFalse(ExternalViewerTypes.isProgram(counted));
		assertTrue("read " + counted.bytesRead,
				counted.bytesRead <= 8 + TAIL + 400_000);
		assertEquals(ZIP, opened(ZIP, album));
		names[names.length - 1] = "AndroidManifest.xml";
		assertRefused("manifest as the last of thousands", zip(names));
		names[names.length - 1] = "photos/installer.apk";
		assertRefused("package as the last of thousands", zip(names));
	}

	@Test
	public void anArchiveInsideAnArchiveIsJudgedByItsOwnEntries()
			throws Exception {
		assertEquals(ZIP, opened(ZIP, zipHoldingAsIs("inner.zip",
				zip("notes.txt", "data.csv"))));
		assertEquals(ZIP, opened(ZIP, zipHoldingAsIs("report.docx",
				zip("[Content_Types].xml", "word/document.xml"))));
		assertRefused("a package kept as it is under another name",
				zipHoldingAsIs("inner.zip", zip("AndroidManifest.xml",
						"resources.arsc")));
	}
}
