package com.professor.zerion.android.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileProviderStagingTest {

	private static final String SRC = "src/main/java/com/professor/zerion/"
			+ "android/";

	private static String read(String path) throws Exception {
		return new String(Files.readAllBytes(Paths.get(path)),
				StandardCharsets.UTF_8);
	}

	@Test
	public void everyScreenThatOpensReceivedFilesUsesTheHandoff()
			throws Exception {
		String opener = read(SRC + "conversation/DocumentOpener.java");
		assertTrue("the document opener hands over through the hand-off",
				opener.contains("ExternalHandoff.open("));
		assertFalse(opener.contains("FileProvider.getUriForFile"));
		for (String screen : new String[] {"channel/ChannelFeedActivity.java",
				"conversation/DocumentOpener.java",
				"conversation/ImageActivity.java",
				"conversation/VideoPlayerActivity.java"}) {
			String s = read(SRC + screen);
			assertTrue(screen, s.contains("ExternalHandoff.open(")
					|| s.contains("ExternalHandoff.openCopy(")
					|| s.contains("DocumentOpener.open("));
			assertFalse(screen + " still serves a copy of its own",
					s.contains("FileProvider.getUriForFile"));
		}
		for (String screen : new String[] {"conversation/AllMediaActivity.java",
				"conversation/ConversationActivity.java"}) {
			String s = read(SRC + screen);
			assertTrue(screen, s.contains("DocumentOpener.open("));
			assertFalse(screen, s.contains("ExternalHandoff.open("));
		}
		assertFalse(read(SRC + "conversation/AllMediaActivity.java")
				.contains("FileProvider.getUriForFile"));
	}

	@Test
	public void theViewerHandsOverOnlyCopiesThatAreWipedAfter()
			throws Exception {
		for (String screen : new String[] {"conversation/ImageActivity.java",
				"conversation/VideoPlayerActivity.java"}) {
			String s = read(SRC + screen);
			assertTrue(screen, s.contains("ExternalHandoff.openCopy(")
					&& s.contains("SaveCleanCopy.startCopy("));
			assertFalse(screen + " leaves a copy for the collector",
					s.contains("ExternalHandoff.open(")
							|| s.contains("SaveCleanCopy.start("));
		}
	}

	@Test
	public void groupPicturesAndVideosOpenOnlyInTheViewer() throws Exception {
		String group = read(SRC + "grouptr/GroupTrConversationActivity.java");
		assertTrue(group.contains("MediaViewer.showImage(")
				&& group.contains("MediaViewer.playVideo("));
		assertFalse("a group hands media to another app by itself",
				group.contains("ExternalHandoff.open("));
		assertFalse(group.contains("FileProvider.getUriForFile"));
		String viewer = read(SRC + "conversation/MediaViewer.java");
		assertFalse("the viewer writes what it shows",
				viewer.contains("FileOutputStream")
						|| viewer.contains("getCacheDir"));
		assertEquals("the viewer puts more than its token in an intent", 1,
				viewer.split("putExtra\\(", -1).length - 1);
		assertTrue(viewer.contains("putExtra(EXTRA_TOKEN, shown.token)"));
	}
}
