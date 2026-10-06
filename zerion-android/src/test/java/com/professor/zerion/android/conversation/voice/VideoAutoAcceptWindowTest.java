package com.professor.zerion.android.conversation.voice;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoAutoAcceptWindowTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();
	private ServiceController<VoiceCallService> controller;

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
		if (controller != null) {
			try {
				controller.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	@Test
	public void theAutoAcceptOfAnAnsweredVideoCallExpires() throws Exception {
		Intent i = new Intent(app, VoiceCallService.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, 7);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, "zt-auto-accept-call");
		i.putExtra("auto_video", true);
		controller = Robolectric.buildService(VoiceCallService.class, i)
				.create().startCommand(0, 1);
		shadowOf(Looper.getMainLooper()).idle();
		VoiceCallService s = controller.get();
		Field f = VoiceCallService.class.getDeclaredField("videoAutoAccept");
		f.setAccessible(true);
		VideoAutoAccept autoAccept = (VideoAutoAccept) f.get(s);
		assertTrue(autoAccept.isArmed());

		Method cancel = VoiceCallService.class.getDeclaredMethod(
				"cancelCallSetupTimeout");
		cancel.setAccessible(true);
		cancel.invoke(s);
		Method connected = VoiceCallService.class.getDeclaredMethod(
				"expireVideoAutoAcceptLater");
		connected.setAccessible(true);
		connected.invoke(s);
		assertTrue("still armed while the call is being set up",
				autoAccept.isArmed());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
		assertFalse(autoAccept.isArmed());
	}
}
