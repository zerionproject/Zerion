package com.professor.zerion.android.security;

import android.app.Dialog;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TapjackingPolicyTest {

	private static boolean dialogFilters() {
		androidx.fragment.app.FragmentActivity a = org.robolectric.Robolectric
				.buildActivity(androidx.fragment.app.FragmentActivity.class)
				.setup().get();
		a.setTheme(com.google.android.material.R.style
				.Theme_MaterialComponents_DayNight_NoActionBar);
		a.getWindow().addFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		SecureDialogs.install(a);
		Dialog d = new SecureAlertDialogBuilder(a).setMessage("x").create();
		d.show();
		return d.getWindow().getDecorView().getFilterTouchesWhenObscured();
	}

	@Test
	public void dialogsFilterObscuredTouchesFromAndroid11() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/security/"
						+ "SecureDialogs.java")), StandardCharsets.UTF_8);
		int i = src.indexOf("public static void applyHostPolicy(Dialog dialog) {");
		String body = src.substring(i, src.indexOf("\n\t}\n", i));
		assertTrue(body.contains(
				"decor != null && android.os.Build.VERSION.SDK_INT >= 30"));
		assertTrue(body.contains("decor.setFilterTouchesWhenObscured(true);"));
	}

	@Test
	@Config(sdk = 29)
	public void android10KeepsTheExistingScreenFilterPolicy() {
		assertEquals(false, dialogFilters());
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
