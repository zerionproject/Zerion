package com.professor.zerion.android.settings;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class MessagingTogglesPerProfileTest {

	private static final String SRC =
			"src/main/java/com/professor/zerion/android/";

	private static final String[] READERS = {
			SRC + "settings/SecurityFragment.java",
			SRC + "settings/NotificationsFragment.java",
			SRC + "conversation/ConversationActivity.java",
			SRC + "AndroidNotificationManagerImpl.java",
			SRC + "conversation/voice/VoiceCallService.java",
			SRC + "conversation/voice/VoiceCallActivity.java",
			SRC + "contact/ContactsViewModel.java"
	};

	private static final String[] KEYS = {
			"PREF_TYPING_INDICATORS", "PREF_VOICE_CALLS_ENABLED",
			"PREF_VIDEO_CALLS_ENABLED", "PREF_NOTIFY_QUICK_REPLY",
			"\"default_disappearing_timer\""
	};

	private static final String[] DEVICE_PREFS = {
			"uiPrefs.", "getUiPrefs()", "uiPreferences()"
	};

	@Test
	public void theTogglesAreNotReadFromTheDeviceWideSettings()
			throws Exception {
		List<String> offending = new ArrayList<>();
		for (String file : READERS) {
			String[] lines = new String(Files.readAllBytes(Paths.get(file)),
					StandardCharsets.UTF_8).split("\n");
			for (int i = 0; i < lines.length; i++) {
				if (!namesAToggle(lines[i])) continue;
				if (lines[i].contains("public static final String")) continue;
				StringBuilder window = new StringBuilder();
				for (int j = Math.max(0, i - 3); j <= i; j++) {
					window.append(lines[j]).append('\n');
				}
				for (String devicePrefs : DEVICE_PREFS) {
					if (window.toString().contains(devicePrefs)) {
						offending.add(file + ":" + (i + 1));
					}
				}
			}
		}
		assertTrue("read from the device-wide settings at " + offending,
				offending.isEmpty());
	}

	private static boolean namesAToggle(String line) {
		for (String key : KEYS) {
			if (line.contains(key)) return true;
		}
		return false;
	}
}
