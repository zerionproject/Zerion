package com.professor.zerion.android.conversation.voice;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.account.UnlockActivity;
import com.professor.zerion.android.api.LockManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowKeyguardManager;
import org.robolectric.shadows.ShadowPendingIntent;
import org.zerionproject.core.api.contact.ContactId;

import java.lang.reflect.Field;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallLockScreenTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 7;
	private static final String CALL_ID = "zt-lock-call";

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

	private ShadowKeyguardManager keyguard() {
		return shadowOf((KeyguardManager) app.getSystemService(
				Context.KEYGUARD_SERVICE));
	}

	private LockManager lockManager() {
		return ((ZerionApplication) app).getApplicationComponent()
				.lockManager();
	}

	private static Object field(Object target, String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private Intent incoming(Class<?> target) {
		Intent i = new Intent(app, target);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, CALL_ID);
		return i;
	}

	private VoiceCallService ringingCallFrom(String contactName)
			throws Exception {
		service = Robolectric.buildService(VoiceCallService.class,
				incoming(VoiceCallService.class)).create().startCommand(0, 1);
		idle();
		VoiceCallService s = service.get();
		assertEquals("RINGING", String.valueOf(field(s, "callState")));
		Field name = VoiceCallService.class.getDeclaredField("contactName");
		name.setAccessible(true);
		name.set(s, contactName);
		shadowOf(app).setComponentNameAndServiceForBindService(
				new ComponentName(app, VoiceCallService.class),
				s.onBind(null));
		return s;
	}

	private VoiceCallActivity openScreen(Intent intent) {
		screen = Robolectric.buildActivity(VoiceCallActivity.class, intent)
				.setup();
		idle();
		return screen.get();
	}

	private static String text(VoiceCallActivity a, int id) {
		return ((TextView) a.findViewById(id)).getText().toString();
	}

	private static boolean ringingShown(VoiceCallActivity a) {
		return a.findViewById(R.id.incoming_call_layout).getVisibility()
				== View.VISIBLE;
	}

	private static String string(Context c, String name) {
		int id = c.getResources().getIdentifier(name, "string",
				c.getPackageName());
		assertNotEquals("missing string " + name, 0, id);
		return c.getString(id);
	}

	private void lockTheApp() {
		keyguard().setIsDeviceSecure(true);
		keyguard().setIsKeyguardSecure(true);
		lockManager().setLocked(true);
		assertTrue(lockManager().isLocked());
	}

	@Test
	public void overTheLockScreenTheRingingScreenIsNeutral() throws Exception {
		keyguard().setKeyguardLocked(true);
		ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));

		assertNotEquals("Alice", text(a, R.id.contact_name));
		assertEquals(string(a, "voice_call_locked_title"),
				text(a, R.id.contact_name));
		assertEquals("the app label is not shown", View.GONE,
				a.findViewById(R.id.call_type_label).getVisibility());
		assertEquals(string(a, "voice_call_locked_hint"),
				text(a, R.id.call_status));
		assertTrue(ringingShown(a));
	}

	@Test
	public void answeringOverTheLockScreenWaitsForThePhoneUnlock()
			throws Exception {
		keyguard().setKeyguardLocked(true);
		keyguard().setIsKeyguardSecure(true);
		VoiceCallService s = ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));

		a.findViewById(R.id.accept_call_button).performClick();
		idle();
		assertEquals("answered without the phone unlock", "RINGING",
				String.valueOf(field(s, "callState")));
		assertTrue("the ringing screen stays", ringingShown(a));
		assertNotEquals("Alice", text(a, R.id.contact_name));

		keyguard().setKeyguardLocked(false);
		idle();
		assertNotEquals("the unlock answers the call", "RINGING",
				String.valueOf(field(s, "callState")));
		assertFalse(ringingShown(a));
	}

	@Test
	public void aCancelledPhoneUnlockLeavesTheCallRinging() throws Exception {
		keyguard().setKeyguardLocked(true);
		keyguard().setIsKeyguardSecure(true);
		VoiceCallService s = ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));

		a.findViewById(R.id.accept_call_button).performClick();
		idle();
		keyguard().setKeyguardLocked(true);
		idle();
		assertEquals("RINGING", String.valueOf(field(s, "callState")));
		assertTrue(ringingShown(a));
	}

	@Test
	public void answeringWhileTheAppIsLockedAsksForTheAppUnlock()
			throws Exception {
		keyguard().setKeyguardLocked(false);
		lockTheApp();
		int running = activitiesRunning();
		VoiceCallService s = ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));
		assertNotEquals("the name is concealed while the app is locked",
				"Alice", text(a, R.id.contact_name));

		a.findViewById(R.id.accept_call_button).performClick();
		idle();
		assertEquals("answered with the app locked", "RINGING",
				String.valueOf(field(s, "callState")));
		ShadowActivity shadow = shadowOf(a);
		ShadowActivity.IntentForResult unlock =
				shadow.getNextStartedActivityForResult();
		assertNotNull("the app unlock is asked for", unlock);
		assertEquals(UnlockActivity.class.getName(),
				unlock.intent.getComponent().getClassName());

		shadow.receiveResult(unlock.intent, android.app.Activity.RESULT_CANCELED,
				null);
		idle();
		assertEquals("a cancelled app unlock leaves the call ringing",
				"RINGING", String.valueOf(field(s, "callState")));

		a.findViewById(R.id.accept_call_button).performClick();
		idle();
		unlock = shadow.getNextStartedActivityForResult();
		assertNotNull(unlock);
		lockManager().setLocked(false);
		shadow.receiveResult(unlock.intent, android.app.Activity.RESULT_OK,
				null);
		idle();
		assertNotEquals("the app unlock answers the call", "RINGING",
				String.valueOf(field(s, "callState")));
		assertFalse("the app lock does not engage again during the call",
				lockManager().isLocked());
		assertEquals("Alice", text(a, R.id.contact_name));

		screen.pause().stop();
		assertEquals("a call screen that is not shown holds no app session",
				running, activitiesRunning());
		screen.destroy();
		screen = null;
		assertEquals("the closed call screen holds no app session",
				running, activitiesRunning());
	}

	private int activitiesRunning() throws Exception {
		Field f = lockManager().getClass().getDeclaredField(
				"activitiesRunning");
		f.setAccessible(true);
		return (Integer) f.get(lockManager());
	}

	@Test
	public void unlockedTheScreenNamesTheCallerAndSaysItIsACall()
			throws Exception {
		keyguard().setKeyguardLocked(false);
		ringingCallFrom("Alice");
		VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));
		assertEquals("Alice", text(a, R.id.contact_name));
		assertEquals(string(a, "voice_call_status_incoming"),
				text(a, R.id.call_status));
		assertEquals(View.VISIBLE,
				a.findViewById(R.id.call_type_label).getVisibility());
	}

	@Test
	public void aDisguisedAppDoesNotNameTheCallerBeforeItsCode()
			throws Exception {
		keyguard().setKeyguardLocked(false);
		com.professor.zerion.android.decoy.DecoyConfig.setEnabled(app, true);
		com.professor.zerion.android.decoy.DecoyConfig.setUnlockCode(app,
				new char[] {'1', '2', '3', '4'});
		try {
			ringingCallFrom("Alice");
			VoiceCallActivity a = openScreen(incoming(VoiceCallActivity.class));
			assertNotEquals("Alice", text(a, R.id.contact_name));
			assertEquals(View.GONE,
					a.findViewById(R.id.call_type_label).getVisibility());
		} finally {
			com.professor.zerion.android.decoy.DecoyConfig.clear(app);
		}
	}

	@Test
	public void theNotificationAcceptOpensTheCallScreenInsteadOfAnswering()
			throws Exception {
		VoiceCallService s = ringingCallFrom("Alice");
		Notification n = new CallNotification(s).build(new ContactId(CONTACT),
				true, CALL_ID, VoiceCallService.CallState.RINGING, false);
		PendingIntent accept = null;
		for (Notification.Action action : n.actions) {
			if ("Accept".contentEquals(action.title)) accept = action.actionIntent;
		}
		assertNotNull(accept);
		ShadowPendingIntent p = shadowOf(accept);
		assertTrue("Accept must not reach the call service directly",
				p.isActivityIntent());
		Intent opened = p.getSavedIntent();
		assertEquals(VoiceCallActivity.class.getName(),
				opened.getComponent().getClassName());
		assertTrue(opened.getBooleanExtra("answer_call", false));
		assertEquals(CALL_ID, opened.getStringExtra(
				VoiceCallActivity.EXTRA_CALL_ID));
	}

	@Test
	public void anAnswerFromTheNotificationOverTheLockScreenWaitsForTheUnlock()
			throws Exception {
		keyguard().setKeyguardLocked(true);
		keyguard().setIsKeyguardSecure(true);
		VoiceCallService s = ringingCallFrom("Alice");
		Intent answer = incoming(VoiceCallActivity.class);
		answer.putExtra("answer_call", true);
		VoiceCallActivity a = openScreen(answer);
		assertEquals("RINGING", String.valueOf(field(s, "callState")));
		assertTrue(ringingShown(a));

		keyguard().setKeyguardLocked(false);
		idle();
		assertNotEquals("RINGING", String.valueOf(field(s, "callState")));
	}

	@Test
	public void theCallNotificationIsNotBundledWithOtherNotifications()
			throws Exception {
		VoiceCallService s = ringingCallFrom("Alice");
		Notification n = new CallNotification(s).build(new ContactId(CONTACT),
				false, CALL_ID, VoiceCallService.CallState.CONNECTED, false);
		assertEquals(CallNotification.GROUP, n.getGroup());
		assertEquals(s.getString(R.string.call_notification_ongoing_title),
				n.extras.getString(Notification.EXTRA_TITLE));
		assertEquals(s.getString(R.string.call_notification_voice_text),
				n.extras.getString(Notification.EXTRA_TEXT));
		Notification ringing = new CallNotification(s).build(
				new ContactId(CONTACT), true, CALL_ID,
				VoiceCallService.CallState.RINGING, false);
		assertEquals(CallNotification.GROUP, ringing.getGroup());
		assertEquals(s.getString(R.string.accept),
				ringing.actions[0].title.toString());
		assertEquals(s.getString(R.string.decline),
				ringing.actions[1].title.toString());
	}
}
