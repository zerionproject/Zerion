package com.professor.zerion.android.conversation.voice;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallScreenTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 7;
	private static final String CALL_ID = "zt-screen-call";
	private static final int OTHER_CONTACT = 8;
	private static final String OTHER_CALL_ID = "zt-second-call";

	private final Application app = ApplicationProvider.getApplicationContext();
	private ServiceController<VoiceCallService> service;
	private ActivityController<VoiceCallActivity> screen;

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
		if (screen != null) {
			try {
				screen.destroy();
			} catch (RuntimeException ignored) {
			}
		}
		if (service != null) {
			try {
				service.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static Field field(String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private Intent incoming(Class<?> target, int contact, String callId) {
		Intent i = new Intent(app, target);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contact);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		return i;
	}

	private VoiceCallService ringingCallFrom(String contactName)
			throws Exception {
		service = Robolectric.buildService(VoiceCallService.class,
				incoming(VoiceCallService.class, CONTACT, CALL_ID))
				.create().startCommand(0, 1);
		idle();
		VoiceCallService s = service.get();
		assertEquals("RINGING", String.valueOf(field("callState").get(s)));
		field("contactName").set(s, contactName);
		shadowOf(app).setComponentNameAndServiceForBindService(
				new ComponentName(app, VoiceCallService.class),
				s.onBind(null));
		return s;
	}

	private VoiceCallActivity openScreen(int contact, String callId) {
		screen = Robolectric.buildActivity(VoiceCallActivity.class,
				incoming(VoiceCallActivity.class, contact, callId)).setup();
		idle();
		return screen.get();
	}

	private static String shownName(VoiceCallActivity a) {
		TextView name = a.findViewById(R.id.contact_name);
		return name.getText().toString();
	}

	@Test
	public void theScreenNamesTheContactWhoIsCalling() throws Exception {
		ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(CONTACT, CALL_ID);
		assertEquals("Alice", shownName(a));
	}

	@Test
	public void aScreenOpenedForAnotherCallDoesNotControlTheRunningCall()
			throws Exception {
		VoiceCallService s = ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(OTHER_CONTACT, OTHER_CALL_ID);
		a.findViewById(R.id.decline_call_button).performClick();
		long deadline = System.currentTimeMillis() + 2_000;
		while (System.currentTimeMillis() < deadline) {
			idle();
			assertFalse("Decline on the second screen ended the running call",
					(Boolean) field("isShuttingDown").get(s));
			assertFalse("Decline on the second screen stopped the running call",
					shadowOf(s).isStoppedBySelf());
			Thread.sleep(20);
		}
		assertEquals("RINGING", String.valueOf(field("callState").get(s)));
		assertTrue("the second screen is closed", a.isFinishing());
	}

	@Test
	public void aSecondOfferDoesNotRetargetTheRunningScreen()
			throws Exception {
		ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(CONTACT, CALL_ID);
		screen.newIntent(incoming(VoiceCallActivity.class, OTHER_CONTACT,
				OTHER_CALL_ID));
		idle();
		assertEquals(CALL_ID, a.getIntent().getStringExtra(
				VoiceCallActivity.EXTRA_CALL_ID));
		assertEquals("Alice", shownName(a));
		assertFalse(a.isFinishing());
	}

	@Test
	public void theSameCallDeliveredAgainKeepsTheScreen() throws Exception {
		ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(CONTACT, CALL_ID);
		screen.newIntent(incoming(VoiceCallActivity.class, CONTACT, CALL_ID));
		idle();
		assertEquals(CALL_ID, a.getIntent().getStringExtra(
				VoiceCallActivity.EXTRA_CALL_ID));
		assertFalse(a.isFinishing());
	}

	@Test
	public void callsAreMatchedByContactAndCallId() {
		assertTrue(VoiceCallService.sameCall(CONTACT, CALL_ID, CONTACT,
				CALL_ID));
		assertTrue("an outgoing screen has no call id yet",
				VoiceCallService.sameCall(CONTACT, null, CONTACT, CALL_ID));
		assertFalse(VoiceCallService.sameCall(CONTACT, CALL_ID,
				OTHER_CONTACT, CALL_ID));
		assertFalse(VoiceCallService.sameCall(CONTACT, CALL_ID, CONTACT,
				OTHER_CALL_ID));
	}
}
