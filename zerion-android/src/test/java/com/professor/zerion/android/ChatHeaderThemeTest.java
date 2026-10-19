package com.professor.zerion.android;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class ChatHeaderThemeTest {

	private static String read(String path) throws Exception {
		return new String(Files.readAllBytes(new File(path).toPath()),
				StandardCharsets.UTF_8);
	}

	@Test
	public void chatAndGroupHeadersSitOnTheScreenBackground() throws Exception {
		String header = read(
				"src/main/res/drawable/bg_toolbar_transparent.xml");
		assertTrue(header, header.contains(
				"android:color=\"?attr/zerionColorBackground\""));
		for (String layout : new String[] {
				"src/main/res/layout/activity_conversation.xml",
				"src/main/res/layout/activity_grouptr_conversation.xml"}) {
			String s = read(layout);
			assertTrue(layout, s.contains("@drawable/bg_toolbar_transparent"));
			int bar = s.indexOf("android:id=\"@+id/appBarLayout\"");
			String attrs = s.substring(bar, s.indexOf('>', bar));
			assertTrue(attrs, attrs.contains("android:elevation=\"0dp\""));
			assertTrue(attrs, attrs.contains("app:elevation=\"0dp\""));
		}
	}
}
