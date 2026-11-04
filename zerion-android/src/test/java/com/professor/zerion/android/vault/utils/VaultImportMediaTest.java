package com.professor.zerion.android.vault.utils;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.professor.zerion.android.attachment.MediaRefusedException;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultImportMediaTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void anImageTooLargeToDecodeIsRefusedWithoutACrash() {
		byte[] jpeg = new byte[4096];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		String outcome;
		try (MockedStatic<BitmapFactory> bf = mockStatic(BitmapFactory.class)) {
			bf.when(() -> BitmapFactory.decodeByteArray(any(byte[].class),
					anyInt(), anyInt(), any(BitmapFactory.Options.class)))
					.thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						if (o.inJustDecodeBounds) {
							o.outWidth = 16000;
							o.outHeight = 300;
							return null;
						}
						throw new OutOfMemoryError("injected");
					});
			try {
				byte[] kept = new MetadataStripper(ctx).stripMetadataOrThrow(
						jpeg.clone(), "image/jpeg");
				outcome = Arrays.equals(jpeg, kept) ? "kept as it is"
						: "changed";
			} catch (MediaRefusedException e) {
				outcome = e.isTooLarge() ? "refused as too large"
						: "refused as not cleanable";
			} catch (OutOfMemoryError e) {
				outcome = "crashed";
			}
		}
		assertEquals("refused as too large", outcome);
	}

	@Test
	public void aFileNoCleanerKnowsIsRefusedAndTextIsKept() {
		MetadataStripper s = new MetadataStripper(ctx);
		byte[] text = "hello".getBytes(StandardCharsets.UTF_8);
		List<String> outcome = new ArrayList<>();
		for (String type : Arrays.asList("application/vnd.ms-excel",
				"application/octet-stream", "application/zip",
				"application/msword", "text/plain", null)) {
			try {
				byte[] kept = s.stripMetadataOrThrow(text.clone(), type);
				outcome.add(type + ": " + (Arrays.equals(text, kept)
						? "kept" : "changed"));
			} catch (MediaRefusedException e) {
				outcome.add(type + ": refused");
			}
		}
		assertEquals(Arrays.asList("application/vnd.ms-excel: refused",
				"application/octet-stream: refused",
				"application/zip: refused", "application/msword: refused",
				"text/plain: kept", "null: refused"), outcome);
	}

	@Test
	public void theDeclaredTypeNeverOverridesWhatTheContentShows() {
		MetadataStripper s = new MetadataStripper(ctx);
		List<String> outcome = new ArrayList<>();
		byte[] jpegAsText = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
				(byte) 0xE1, 0, 0x10, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0,
				42};
		byte[] zipAsDocx = {'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0};
		byte[] textWithNul = {'h', 'i', 0, '!'};
		Object[][] cases = {{jpegAsText, "text/plain"},
				{zipAsDocx, "application/vnd.openxmlformats-officedocument"
						+ ".wordprocessingml.document"},
				{textWithNul, "text/plain"},
				{"<html></html>".getBytes(StandardCharsets.UTF_8), "text/html"},
				{"notes".getBytes(StandardCharsets.UTF_8), "text/plain"}};
		for (Object[] c : cases) {
			byte[] data = (byte[]) c[0];
			try {
				byte[] kept = s.stripMetadataOrThrow(data.clone(), (String) c[1]);
				outcome.add(Arrays.equals(data, kept) ? "kept" : "changed");
			} catch (MediaRefusedException e) {
				outcome.add("refused");
			}
		}
		assertEquals(Arrays.asList("refused", "refused", "refused", "refused",
				"kept"), outcome);
	}

	@Test
	public void aFileWithoutADeclaredTypeIsRecognisedByItsContent() {
		byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
		byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
				0, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1};
		byte[] pdf = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);
		byte[] zip = {'P', 'K', 3, 4, 0, 0, 0, 0};
		byte[] mp4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
		assertEquals("image/png", MetadataStripper.sniffedType(png));
		assertEquals("image/jpeg", MetadataStripper.sniffedType(jpeg));
		assertEquals("application/pdf", MetadataStripper.sniffedType(pdf));
		assertEquals("application/vnd.openxmlformats-officedocument",
				MetadataStripper.sniffedType(zip));
		assertEquals("video/mp4", MetadataStripper.sniffedType(mp4));
		assertEquals("application/octet-stream",
				MetadataStripper.sniffedType("hello".getBytes(
						StandardCharsets.UTF_8)));
	}

	@Test
	public void aZipOfficeDocumentLosesItsPropertiesParts() throws Exception {
		java.io.ByteArrayOutputStream bout =
				new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream z =
				new java.util.zip.ZipOutputStream(bout)) {
			String types = "<?xml version=\"1.0\"?><Types><Default Extension="
					+ "\"xml\" ContentType=\"application/xml\"/><Override "
					+ "PartName=\"/docProps/core.xml\" ContentType=\"a/b\"/>"
					+ "<Override PartName=\"/word/document.xml\" ContentType="
					+ "\"c/d\"/><ct:Override ContentType='e/f'\n PartName="
					+ "'/DocProps/app.xml'></ct:Override></Types>";
			String rels = "<Relationships><Relationship Id=\"rId1\" Type=\"t\""
					+ " Target=\"word/document.xml\"/><Relationship Id=\"rId2\""
					+ " Type=\"u\" Target=\"docProps/core.xml\"/><Relationship "
					+ "Id=\"rId3\" Type=\"v\" Target='/docProps/app.xml'>"
					+ "</Relationship></Relationships>";
			for (String[] entry : new String[][] {
					{"[Content_Types].xml", types}, {"_rels/.rels", rels},
					{"DocProps/core.xml", "<author>me</author>"},
					{"docProps/app.xml", "<app>Word</app>"},
					{"word/document.xml", "<w/>"}}) {
				z.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
				z.write(entry[1].getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		byte[] cleaned = new MetadataStripper(ctx).stripMetadataOrThrow(
				bout.toByteArray(), "application/vnd.openxmlformats"
						+ "-officedocument.wordprocessingml.document");
		List<String> names = new ArrayList<>();
		java.util.Map<String, String> parts = new java.util.HashMap<>();
		try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(
				new java.io.ByteArrayInputStream(cleaned))) {
			java.util.zip.ZipEntry e;
			while ((e = z.getNextEntry()) != null) {
				names.add(e.getName());
				java.io.ByteArrayOutputStream p =
						new java.io.ByteArrayOutputStream();
				byte[] b = new byte[1024];
				int r;
				while ((r = z.read(b)) > 0) p.write(b, 0, r);
				parts.put(e.getName(), new String(p.toByteArray(),
						StandardCharsets.UTF_8));
			}
		}
		assertEquals(Arrays.asList("[Content_Types].xml", "_rels/.rels",
				"word/document.xml"), names);
		assertEquals("<?xml version=\"1.0\"?><Types><Default Extension=\"xml\""
				+ " ContentType=\"application/xml\"/><Override PartName="
				+ "\"/word/document.xml\" ContentType=\"c/d\"/></Types>",
				parts.get("[Content_Types].xml"));
		assertEquals("<Relationships><Relationship Id=\"rId1\" Type=\"t\" "
				+ "Target=\"word/document.xml\"/></Relationships>",
				parts.get("_rels/.rels"));
		assertEquals("<w/>", parts.get("word/document.xml"));
	}

	private static byte[] sample(int size, int index) {
		byte[] s = new byte[size];
		for (int i = 0; i < size; i++) s[i] = (byte) (i * 31 + index);
		return s;
	}

	@Test
	public void aVaultVideoWithLargeKeyFramesIsRemuxedInFull()
			throws Exception {
		int[] sizes = {3 * 1024 * 1024, 40 * 1024, 5 * 1024 * 1024 + 17};
		int[] index = {0};
		List<byte[]> written = new ArrayList<>();
		byte[] original = new byte[128];
		byte[] result;
		try (MockedConstruction<MediaExtractor> ignored =
				mockConstruction(MediaExtractor.class, (m, c) -> {
					when(m.getTrackCount()).thenReturn(1);
					when(m.getTrackFormat(0)).thenReturn(
							MediaFormat.createVideoFormat("video/hevc", 3840,
									2160));
					when(m.getSampleSize()).thenAnswer(inv ->
							index[0] < sizes.length ? (long) sizes[index[0]]
									: -1L);
					when(m.readSampleData(any(ByteBuffer.class), anyInt()))
							.thenAnswer(inv -> {
								if (index[0] >= sizes.length) return -1;
								ByteBuffer b = inv.getArgument(0);
								int size = sizes[index[0]];
								if (b.capacity() < size) {
									throw new IllegalArgumentException();
								}
								b.clear();
								b.put(sample(size, index[0]));
								b.position(0);
								b.limit(size);
								return size;
							});
					when(m.advance()).thenAnswer(inv -> ++index[0]
							< sizes.length);
					when(m.getSampleTime()).thenAnswer(inv ->
							index[0] * 33_333L);
				});
				MockedConstruction<MediaMuxer> ignoredToo =
						mockConstruction(MediaMuxer.class, (m, c) -> {
							when(m.addTrack(any())).thenReturn(0);
							doAnswer(inv -> {
								ByteBuffer b = inv.getArgument(1);
								MediaCodec.BufferInfo info =
										inv.getArgument(2);
								byte[] got = new byte[info.size];
								ByteBuffer view = b.duplicate();
								view.position(info.offset);
								view.get(got);
								written.add(got);
								return null;
							}).when(m).writeSampleData(anyInt(), any(),
									any());
							String path = (String) c.arguments().get(0);
							doAnswer(inv -> {
								java.nio.file.Files.write(
										new java.io.File(path).toPath(),
										Mp4MetadataScrubberTest.box("moov",
												Mp4MetadataScrubberTest.box(
														"mvhd",
														new byte[100])));
								return null;
							}).when(m).stop();
						})) {
			result = new MetadataStripper(ctx).stripMetadataOrThrow(
					original.clone(), "video/mp4");
		}
		int intact = 0;
		for (int i = 0; i < written.size() && i < sizes.length; i++) {
			if (Arrays.equals(sample(sizes[i], i), written.get(i))) intact++;
		}
		assertEquals("samples remuxed 3 of 3, original with its metadata"
						+ " kept: no",
				"samples remuxed " + intact + " of " + sizes.length
						+ ", original with its metadata kept: "
						+ (Arrays.equals(original, result) ? "yes" : "no"));
	}
}
