package com.professor.zerion.android.util;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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
				"grouptr/GroupTrConversationActivity.java"}) {
			String s = read(SRC + screen);
			assertTrue(screen, s.contains("ExternalHandoff.open(")
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
}
