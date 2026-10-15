package com.professor.zerion.android;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoCallsInChannelsAndGroupsTest {

	private static final File SRC =
			new File("src/main/java/com/professor/zerion/android");
	private static final File RES = new File("src/main/res");
	private static final List<String> SCREENS =
			Arrays.asList("channel", "grouptr");
	private static final List<String> CALL_CODE = Arrays.asList(
			"action_voice_call", "action_video_call", "R.string.voice_call",
			"R.string.video_call", "R.menu.conversation_actions",
			"VoiceCallActivity", "VoiceCallService", "CallIntents",
			"CallNotification", "VideoStreamManager", "startVoiceCall",
			"startVideoCall", "R.drawable.ic_phone", "R.drawable.ic_videocam",
			"R.drawable.ic_call_", "R.layout.activity_voice_call");
	private static final List<String> CALL_RESOURCES = Arrays.asList(
			"@+id/action_voice_call", "@id/action_voice_call",
			"@+id/action_video_call", "@id/action_video_call",
			"@string/voice_call", "@string/video_call", "@drawable/ic_phone",
			"@drawable/ic_videocam", "@drawable/ic_call_",
			"@layout/activity_voice_call");
	private static final Pattern REFERENCE =
			Pattern.compile("R\\.(layout|menu)\\.([a-z0-9_]+)");
	private static final Pattern NESTED =
			Pattern.compile("@layout/([a-z0-9_]+)");

	@Test
	public void channelAndGroupScreensOfferNoCall() throws IOException {
		List<String> problems = new ArrayList<>();
		Set<String> resources = new TreeSet<>();
		for (String screen : SCREENS) {
			for (File f : javaFiles(new File(SRC, screen))) {
				String text = read(f);
				for (String token : CALL_CODE) {
					if (text.contains(token)) {
						problems.add(f.getName() + " uses " + token);
					}
				}
				Matcher m = REFERENCE.matcher(text);
				while (m.find()) resources.add(m.group(1) + "/" + m.group(2));
			}
		}
		assertTrue(resources.contains("layout/activity_channel_feed"));
		assertTrue(resources.contains("layout/activity_grouptr_conversation"));
		assertTrue(resources.contains("menu/grouptr_post_actions"));
		for (String r : withNested(resources)) {
			String text = read(new File(RES, r + ".xml"));
			for (String token : CALL_RESOURCES) {
				if (text.contains(token)) {
					problems.add(r + ".xml carries " + token);
				}
			}
		}
		assertEquals(String.join("\n", problems), 0, problems.size());
	}

	@Test
	public void theCheckFindsTheCallsOfOneToOneChats() throws IOException {
		String menu = read(new File(RES, "menu/conversation_actions.xml"));
		String activity = read(new File(SRC,
				"conversation/ConversationActivity.java"));
		assertTrue(menu.contains("@+id/action_voice_call"));
		assertTrue(menu.contains("@+id/action_video_call"));
		assertTrue(activity.contains("action_voice_call"));
		assertFalse(read(new File(RES, "menu/grouptr_post_actions.xml"))
				.contains("call"));
	}

	private static Set<String> withNested(Set<String> start)
			throws IOException {
		Set<String> seen = new TreeSet<>();
		Deque<String> todo = new ArrayDeque<>(start);
		while (!todo.isEmpty()) {
			String r = todo.pop();
			if (!seen.add(r)) continue;
			File f = new File(RES, r + ".xml");
			assertTrue("missing " + f, f.isFile());
			Matcher m = NESTED.matcher(read(f));
			while (m.find()) todo.push("layout/" + m.group(1));
		}
		return seen;
	}

	private static List<File> javaFiles(File dir) {
		List<File> out = new ArrayList<>();
		File[] children = dir.listFiles();
		assertTrue("missing " + dir, children != null && children.length > 0);
		for (File c : children) {
			if (c.isDirectory()) out.addAll(javaFiles(c));
			else if (c.getName().endsWith(".java")) out.add(c);
		}
		return out;
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}
}
