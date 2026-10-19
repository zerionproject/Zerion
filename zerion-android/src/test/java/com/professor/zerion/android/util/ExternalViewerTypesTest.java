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
