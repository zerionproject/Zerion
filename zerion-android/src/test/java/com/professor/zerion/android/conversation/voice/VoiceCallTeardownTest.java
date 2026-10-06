package com.professor.zerion.android.conversation.voice;

import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.crypto.SecretKey;

import java.lang.reflect.Field;
import java.util.Arrays;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallTeardownTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 7;
	private static final String CALL_ID = "zt-teardown-call";

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

	private static Intent incoming(int contact, String callId) {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VoiceCallService.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, contact);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		return i;
	}

	private static Object field(Object o, String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static String state(VoiceCallService s) throws Exception {
		return String.valueOf(field(s, "callState"));
	}

	private void awaitStopped(long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			shadowOf(Looper.getMainLooper()).idle();
			if (shadowOf(controller.get()).isStoppedBySelf()) return;
			Thread.sleep(20);
		}
	}

	private SecretKey ringWithHeldKey() throws Exception {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x5A);
		SecretKey key = new SecretKey(raw);
		byte[] eph = new byte[32];
		Arrays.fill(eph, (byte) 0x3C);
		VoiceCallKeyHolder.setOffer(CONTACT, CALL_ID, key, eph);
		controller = Robolectric.buildService(VoiceCallService.class,
				incoming(CONTACT, CALL_ID)).create().startCommand(0, 1);
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals("RINGING", state(controller.get()));
		assertTrue("the service took the offer's key",
				field(controller.get(), "voiceCallKey") == key);
		return key;
	}

	@Test
	public void aCallWhoseSetupFailsIsWipedAndTheServiceStops()
			throws Exception {
		SecretKey key = ringWithHeldKey();
		Intent accept = new Intent(CallIntents.ACTION_ACCEPT_CALL);
		controller.withIntent(accept).startCommand(0, 2);
		awaitStopped(20_000);
		VoiceCallService s = controller.get();
		assertEquals("FAILED", state(s));
		assertTrue("the service stops itself after a failed setup",
				shadowOf(s).isStoppedBySelf());
		assertNull("the call key is dropped", field(s, "voiceCallKey"));
		assertTrue("the call key bytes are wiped",
				Arrays.equals(new byte[32], key.getBytes()));
		assertNull(field(s, "localEphemeralSecret"));
		assertNull(field(s, "remoteEphemeralSecret"));
	}

	@Test
	public void aServiceBeingTornDownTakesNoFurtherCall() throws Exception {
		ringWithHeldKey();
		controller.withIntent(new Intent(CallIntents.ACTION_ACCEPT_CALL))
				.startCommand(0, 2);
		awaitStopped(20_000);
		VoiceCallService s = controller.get();
		assertTrue(shadowOf(s).isStoppedBySelf());
		Object contactBefore = field(s, "contactId");
		controller.withIntent(incoming(CONTACT + 1, "zt-second-call"))
				.startCommand(0, 3);
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals("FAILED", state(s));
		assertTrue("the dying instance kept the first call's identity",
				field(s, "contactId") == contactBefore);
		assertEquals(CALL_ID, field(s, "callId"));
		assertEquals("the refused start does not keep the dying instance"
				+ " started", 3, shadowOf(s).getStopSelfId());
	}
}
