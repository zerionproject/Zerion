package com.professor.zerion.android.security;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TapjackingPolicyTest {

	@Test
	public void dialogsFilterObscuredTouchesLikeTheScreens() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/security/"
						+ "SecureDialogs.java")), StandardCharsets.UTF_8);
		int i = src.indexOf("public static void applyHostPolicy(Dialog dialog) {");
		String body = src.substring(i, src.indexOf("\n\t}\n", i));
		assertTrue(body.contains("filtersCoveredTouches("));
		assertTrue(body.contains("decor.setFilterTouchesWhenObscured(true);"));
	}

	@Test
	public void theCallScreenHidesOverlaysAndFiltersItsButtons()
			throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/conversation/voice/"
						+ "VoiceCallActivity.java")), StandardCharsets.UTF_8);
		assertTrue(src.contains("getWindow().setHideOverlayWindows(true);"));
		assertTrue(src.contains(
				"acceptCallButton.setFilterTouchesWhenObscured(true);"));
		assertTrue(src.contains(
				"declineCallButton.setFilterTouchesWhenObscured(true);"));
	}
}
