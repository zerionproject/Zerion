package com.professor.zerion.android.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExternalViewerTypesTest {

	@Test
	public void activeTypesAreShownAsPlainText() {
		for (String t : new String[] {"text/html", "TEXT/HTML",
				"text/html; charset=utf-8", " text/html ",
				"application/xhtml+xml", "image/svg+xml", "text/xml",
				"application/xml", "application/javascript",
				"text/javascript", "application/vnd.apple.mpegurl",
				"application/x-mpegURL", "audio/x-mpegurl", "audio/mpegurl",
				"application/dash+xml", "application/rss+xml",
				"message/rfc822", "text/uri-list", "text/css",
				"application/x-shockwave-flash", "text/x-html-fragment"}) {
			assertEquals(t, ExternalViewerTypes.TEXT,
					ExternalViewerTypes.effectiveType(t));
		}
	}

	@Test
	public void typesThatMakeAViewerFetchOrStreamAreShownAsPlainText() {
		for (String t : new String[] {"audio/x-scpls", "video/x-ms-asf",
				"video/x-ms-asx", "audio/x-ms-wax", "video/x-ms-wvx",
				"application/vnd.ms-wpl", "application/sdp",
				"application/smil", "application/smil+xml",
				"video/vnd.mpeg.dash.mpd", "application/x-bittorrent",
				"text/markdown", "text/x-markdown", "text/x-vcalendar",
				"text/rtf", "image/svg", "image/svg-xml",
				"application/x-mimearchive", "multipart/x-mixed-replace",
				"message/external-body", "application/epub+zip",
				"audio/x-pn-realaudio", "application/xspf+xml",
				"application/x-quicktime-media-link"}) {
			assertEquals(t, ExternalViewerTypes.TEXT,
					ExternalViewerTypes.effectiveType(t));
		}
	}

	private static byte[] zip(String... names) throws Exception {
		java.io.ByteArrayOutputStream out =
				new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream z =
				new java.util.zip.ZipOutputStream(out)) {
			for (String name : names) {
				z.putNextEntry(new java.util.zip.ZipEntry(name));
				z.write(("content of " + name)
						.getBytes(StandardCharsets.UTF_8));
				z.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private static byte[] after(int junk, byte[] content) {
		byte[] out = new byte[junk + content.length];
		System.arraycopy(content, 0, out, junk, content.length);
		return out;
	}

	@Test
	public void aTypeTheSystemInstallsOrImportsIsNeverOpened()
			throws Exception {
		byte[] plain = zip("readme.md", "data/numbers.csv");
		for (String t : new String[] {
				"application/vnd.android.package-archive",
				"Application/VND.Android.Package-Archive; x=1",
				"application/x-apk", "application/vnd.apkm",
				"application/xapk-package-archive",
				"application/vnd.android.dex", "application/x-dex",
				"application/java-archive", "application/x-java-archive",
				"application/x-executable", "application/x-elf",
				"application/x-msdownload", "application/x-sharedlib",
				"application/x-x509-ca-cert", "application/x-x509-user-cert",
				"application/x-x509-server-cert", "application/pkix-cert",
				"application/pkix-crl", "application/x-pkcs12",
				"application/pkcs12", "application/x-pkcs7-certificates",
				"application/x-pkcs7-certreqresp", "application/pkcs7-mime",
				"application/pkcs7-signature", "application/x-pkcs7-mime",
				"application/pkcs8", "application/pkcs10",
				"application/x-pem-file", "application/x-wifi-config",
				"application/vnd.wfa.wsc", "application/vnd.oma.drm.message",
				"application/x-android-drm-fl",
				"application/x-apple-aspen-config",
				"vnd.android.cursor.item/contact",
				"vnd.android.cursor.dir/person", "x-scheme-handler/http",
				"inode/directory", "x-world/x-vrml"}) {
			assertEquals(t, null,
					ExternalViewerTypes.typeForContent(t, plain));
			assertEquals(t, null, ExternalViewerTypes.typeForContent(t,
					"just some words".getBytes(StandardCharsets.UTF_8)));
		}
		for (String t : new String[] {"application/webbundle",
				"application/x-webbundle"}) {
			assertEquals(t, ExternalViewerTypes.TEXT,
					ExternalViewerTypes.typeForContent(t, plain));
		}
	}

	@Test
	public void aProgramIsNeverOpenedWhateverItsTypeSays() throws Exception {
		byte[] apk = zip("META-INF/MANIFEST.MF", "AndroidManifest.xml",
				"classes.dex", "resources.arsc");
		byte[] manifestFirst = zip("AndroidManifest.xml");
		byte[] dex = after(0, "dex\n035\0 and the rest of it follows here"
				.getBytes(StandardCharsets.ISO_8859_1));
		byte[] elf = after(0, "\u007FELF\2\1\1 and the rest of it follows"
				.getBytes(StandardCharsets.ISO_8859_1));
		for (String t : new String[] {"application/zip",
				"application/octet-stream", "application/pdf",
				"application/x-zip-compressed", "image/jpeg", "audio/mpeg",
				"text/plain", "text/html", "not a type", null}) {
			for (byte[] program : new byte[][] {apk, manifestFirst,
					after(700, apk), after(70_000, apk), dex, elf}) {
				assertEquals(String.valueOf(t), null,
						ExternalViewerTypes.typeForContent(t, program));
			}
		}
		byte[] plain = zip("readme.md", "src/main/AndroidManifest.xml",
				"AndroidManifest.xml.txt", "androidmanifest.xml");
		assertEquals("application/zip",
				ExternalViewerTypes.typeForContent("application/zip", plain));
		assertEquals(ExternalViewerTypes.OPAQUE, ExternalViewerTypes
				.typeForContent("application/octet-stream", plain));
		assertEquals(ExternalViewerTypes.TEXT,
				ExternalViewerTypes.typeForContent("text/plain",
						"AndroidManifest.xml is only mentioned here PK\5\6"
								.getBytes(StandardCharsets.ISO_8859_1)));
		byte[] comment = zip("notes.txt");
		byte[] withComment = after(0, comment);
		byte[] tail = "AndroidManifest.xml".getBytes(StandardCharsets.UTF_8);
		byte[] commented = new byte[withComment.length + tail.length];
		System.arraycopy(withComment, 0, commented, 0, withComment.length);
		System.arraycopy(tail, 0, commented, withComment.length, tail.length);
		commented[withComment.length - 2] = (byte) tail.length;
		assertEquals("application/zip", ExternalViewerTypes
				.typeForContent("application/zip", commented));
	}

	@Test
	public void passiveTypesAreKept() {
		for (String t : new String[] {"image/jpeg", "image/png",
				"image/webp", "video/mp4", "audio/ogg", "audio/mpeg",
				"application/pdf", "text/plain", "application/zip",
				"application/vnd.openxmlformats-officedocument"
						+ ".wordprocessingml.document"}) {
			assertEquals(t, t, ExternalViewerTypes.effectiveType(t));
		}
		assertEquals("image/jpeg",
				ExternalViewerTypes.effectiveType("Image/JPEG; q=1"));
	}

	@Test
	public void malformedTypesBecomeOpaque() {
		for (String t : new String[] {null, "", "html", "text/", "/html",
				"text/html/x", "text/ht ml", "text\u0000/html",
				"*/*"}) {
			assertEquals(String.valueOf(t), ExternalViewerTypes.OPAQUE,
					ExternalViewerTypes.effectiveType(t));
		}
	}

	@Test
	public void everyExternalViewerChecksTheContent() throws Exception {
		String[] files = {
				"src/main/java/com/professor/zerion/android/channel/"
						+ "ChannelFeedActivity.java",
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "DocumentOpener.java",
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "ImageActivity.java",
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "VideoPlayerActivity.java"};
		String viewer = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/conversation/"
						+ "MediaViewer.java")), StandardCharsets.UTF_8);
		assertTrue("the viewer shows content it has not checked",
				viewer.replaceAll("\\s+", "")
						.contains("ExternalViewerTypes.typeForContent("));
		String group = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/grouptr/"
						+ "GroupTrConversationActivity.java")),
				StandardCharsets.UTF_8);
		assertTrue("group media skips the checked viewer",
				group.contains("MediaViewer.showImage(")
						&& group.contains("MediaViewer.playVideo("));
		for (String f : new String[] {"AllMediaActivity.java",
				"ConversationActivity.java"}) {
			String src = new String(Files.readAllBytes(Paths.get(
					"src/main/java/com/professor/zerion/android/conversation/"
							+ f)), StandardCharsets.UTF_8);
			assertTrue(f + " opens documents through the checked opener",
					src.contains("DocumentOpener.open("));
			assertFalse(f, src.contains("Intent.ACTION_VIEW"));
		}
		for (String f : files) {
			String src = new String(Files.readAllBytes(Paths.get(f)),
					StandardCharsets.UTF_8);
			String flat = src.replaceAll("\\s+", "");
			assertTrue(f, flat.contains("ExternalViewerTypes.typeForContent(")
					|| flat.contains("ExternalViewerTypes.typeForFile("));
			assertTrue(f, flat.contains("Type==null){")
					|| flat.contains("type==null){"));
			assertFalse(f, src.contains("setDataAndType(shareUri, mime)"));
			assertFalse(f, src.contains(
					"String mime = item.header.getContentType();"));
		}
	}
}
