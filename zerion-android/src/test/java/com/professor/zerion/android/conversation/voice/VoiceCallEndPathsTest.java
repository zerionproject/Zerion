package com.professor.zerion.android.conversation.voice;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalHeader;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallEndPathsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 7;
	private static final String CALL_ID = "zt-end-paths-call";

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

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static Field field(String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static Object invoke(VoiceCallService s, String name,
			Class<?>[] types, Object... args) throws Exception {
		Method m = VoiceCallService.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		return m.invoke(s, args);
	}

	private void awaitStopped(long timeoutMs) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			idle();
			if (shadowOf(controller.get()).isStoppedBySelf()) return;
			Thread.sleep(20);
		}
	}

	private VoiceCallService ringingAtThePeer(SecretKey key) throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VoiceCallService.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, CALL_ID);
		controller = Robolectric.buildService(VoiceCallService.class, i)
				.create().startCommand(0, 1);
		idle();
		VoiceCallService s = controller.get();
		invoke(s, "cancelCallSetupTimeout", new Class<?>[0]);
		field("isIncoming").set(s, false);
		field("voiceCallKey").set(s, key);
		field("isRecording").set(s, true);
		field("callState").set(s, VoiceCallService.CallState.RINGING);
		return s;
	}

	private static void peerSends(VoiceCallService s, VoiceSignalType type,
			String payload) throws Exception {
		VoiceSignalHeader h = new VoiceSignalHeader(new MessageId(new byte[32]),
				new GroupId(new byte[32]), System.currentTimeMillis(), false,
				type, CALL_ID, payload, null);
		invoke(s, "handleIncomingVoiceSignal",
				new Class<?>[] {VoiceSignalHeader.class}, h);
		idle();
	}

	private static SecretKey key() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x44);
		return new SecretKey(raw);
	}

	private void assertTornDown(VoiceCallService s, SecretKey key)
			throws Exception {
		awaitStopped(20_000);
		assertTrue("the service stops", shadowOf(s).isStoppedBySelf());
		assertTrue("the teardown ran",
				(Boolean) field("isShuttingDown").get(s));
		assertFalse("the streams stopped", (Boolean) field("isRecording").get(s));
		assertTrue("the endpoints were closed",
				(Boolean) field("endpointsClosed").get(s));
		assertNull("the key is dropped", field("voiceCallKey").get(s));
		assertTrue("the key bytes are wiped",
				Arrays.equals(new byte[32], key.getBytes()));
	}

	@Test
	public void aRejectFromThePeerRunsTheFullTeardown() throws Exception {
		SecretKey key = key();
		VoiceCallService s = ringingAtThePeer(key);
		peerSends(s, VoiceSignalType.CALL_REJECT, null);
		assertTornDown(s, key);
	}

	@Test
	public void aBusyPeerRunsTheFullTeardown() throws Exception {
		SecretKey key = key();
		VoiceCallService s = ringingAtThePeer(key);
		peerSends(s, VoiceSignalType.CALL_BUSY, null);
		assertTornDown(s, key);
	}

	@Test
	public void aConnectionThatArrivesAfterTheTeardownDoesNotReviveTheCall()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTING);
		s.endCall();
		idle();
		AtomicInteger disposed = new AtomicInteger();
		DuplexTransportConnection late = connection(disposed);
		boolean adopted = (Boolean) invoke(s, "adoptConnection",
				new Class<?>[] {DuplexTransportConnection.class}, late);
		assertFalse(adopted);
		assertEquals("the late connection is closed", 2, disposed.get());
		assertEquals(VoiceCallService.CallState.DISCONNECTED,
				field("callState").get(s));
		assertNull(field("torConnection").get(s));
	}

	@Test
	public void theCallStartIsRecordedOnceAndKeptAcrossAReconnection()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		assertEquals(0L, s.getConnectedAtRealtime());
		field("callState").set(s, VoiceCallService.CallState.CONNECTING);
		AtomicInteger disposed = new AtomicInteger();
		assertTrue((Boolean) invoke(s, "adoptConnection",
				new Class<?>[] {DuplexTransportConnection.class},
				connection(disposed)));
		long start = s.getConnectedAtRealtime();
		assertTrue("the first connection records the call start", start > 0);
		SystemClock.setCurrentTimeMillis(System.currentTimeMillis() + 7_000);
		field("callState").set(s, VoiceCallService.CallState.CONNECTING);
		assertTrue((Boolean) invoke(s, "adoptConnection",
				new Class<?>[] {DuplexTransportConnection.class},
				connection(disposed)));
		assertEquals("a reconnection keeps the start", start,
				s.getConnectedAtRealtime());
		s.endCall();
		idle();
	}

	@Test
	public void theLivenessWatchdogEndsACallWithNoMediaForTheDeadline()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		field("lastMediaRealtime").set(s,
				SystemClock.elapsedRealtime() - 91_000L);
		invoke(s, "onReconnectDeadlineReached", new Class<?>[0]);
		idle();
		assertEquals("a call whose last media is older than the deadline ends,"
				+ " however its reader is blocked",
				VoiceCallService.CallState.DISCONNECTED,
				field("callState").get(s));
	}

	@Test
	public void theLivenessWatchdogLeavesACallWithRecentMediaAlone()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		field("lastMediaRealtime").set(s, SystemClock.elapsedRealtime());
		invoke(s, "onReconnectDeadlineReached", new Class<?>[0]);
		idle();
		assertEquals("a call that received media within the deadline is not"
				+ " ended; the watchdog reschedules",
				VoiceCallService.CallState.CONNECTED,
				field("callState").get(s));
		s.endCall();
		idle();
	}

	@Test
	public void aTransientStallShorterThanTheDeadlineDoesNotEndTheCall()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		field("lastMediaRealtime").set(s,
				SystemClock.elapsedRealtime() - 60_000L);
		invoke(s, "onReconnectDeadlineReached", new Class<?>[0]);
		idle();
		assertEquals("a 60 s stall (under the 90 s deadline) keeps the call",
				VoiceCallService.CallState.CONNECTED,
				field("callState").get(s));
		s.endCall();
		idle();
	}

	@Test
	public void theGiveUpIsTimedFromTheLastMediaNotFromDetection()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		field("reconnectAttempts").set(s, 1);
		field("lastMediaRealtime").set(s,
				SystemClock.elapsedRealtime() - 91_000L);
		invoke(s, "handleConnectionError", new Class<?>[0]);
		idle();
		assertEquals("a loss whose last media is past the deadline ends rather"
				+ " than retrying, whatever role this side holds",
				VoiceCallService.CallState.DISCONNECTED,
				field("callState").get(s));
	}

	@Test
	public void aLossWithinTheDeadlineAndAttemptBudgetReconnects()
			throws Exception {
		VoiceCallService s = ringingAtThePeer(key());
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		field("reconnectAttempts").set(s, 0);
		field("lastMediaRealtime").set(s, SystemClock.elapsedRealtime());
		field("onionAddress").set(s, null);
		field("lastRemoteOnion").set(s, null);
		invoke(s, "handleConnectionError", new Class<?>[0]);
		idle();
		assertEquals("within the budget the call attempts to reconnect",
				VoiceCallService.CallState.CONNECTING,
				field("callState").get(s));
		assertTrue("the media-liveness clock is armed",
				(long) field("lastMediaRealtime").get(s) > 0);
		s.endCall();
		idle();
	}

	@Test
	public void aCallThatArrivesDuringTheTeardownIsReplayedToTheNextInstance()
			throws Exception {
		SecretKey key = key();
		VoiceCallService s = ringingAtThePeer(key);
		s.endCall();
		idle();
		assertTrue((Boolean) field("isShuttingDown").get(s));
		Intent next = new Intent(ApplicationProvider.getApplicationContext(),
				VoiceCallService.class);
		next.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CONTACT);
		next.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		next.putExtra(VoiceCallActivity.EXTRA_CALL_ID, "the-next-call");
		Intent accept = new Intent(ApplicationProvider.getApplicationContext(),
				VoiceCallService.class);
		accept.setAction(CallIntents.ACTION_ACCEPT_CALL);
		s.onStartCommand(next, 0, 2);
		s.onStartCommand(accept, 0, 3);
		assertEquals("the dying instance does not take the new call",
				VoiceCallService.CallState.DISCONNECTED, field("callState").get(s));
		awaitStopped(20_000);
		controller.destroy();
		idle();
		Intent first = org.robolectric.Shadows.shadowOf((android.app.Application) ApplicationProvider.getApplicationContext())
				.getNextStartedService();
		while (first != null && !"the-next-call".equals(
				first.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID))) {
			first = org.robolectric.Shadows.shadowOf((android.app.Application) ApplicationProvider.getApplicationContext())
					.getNextStartedService();
		}
		assertNotNull("the incoming call is handed to the next instance", first);
		Intent second = org.robolectric.Shadows.shadowOf((android.app.Application) ApplicationProvider.getApplicationContext())
				.getNextStartedService();
		assertNotNull("the accept follows it", second);
		assertEquals(CallIntents.ACTION_ACCEPT_CALL, second.getAction());
	}

	private static DuplexTransportConnection connection(AtomicInteger disposed) {
		TransportConnectionReader reader = (TransportConnectionReader)
				Proxy.newProxyInstance(
						TransportConnectionReader.class.getClassLoader(),
						new Class<?>[] {TransportConnectionReader.class},
						(p, m, a) -> {
							if (m.getName().equals("dispose")) {
								disposed.incrementAndGet();
							}
							return null;
						});
		TransportConnectionWriter writer = (TransportConnectionWriter)
				Proxy.newProxyInstance(
						TransportConnectionWriter.class.getClassLoader(),
						new Class<?>[] {TransportConnectionWriter.class},
						(p, m, a) -> {
							if (m.getName().equals("dispose")) {
								disposed.incrementAndGet();
							}
							Class<?> r = m.getReturnType();
							if (r == int.class) return 0;
							if (r == long.class) return 0L;
							if (r == boolean.class) return false;
							return null;
						});
		return (DuplexTransportConnection) Proxy.newProxyInstance(
				DuplexTransportConnection.class.getClassLoader(),
				new Class<?>[] {DuplexTransportConnection.class},
				(p, m, a) -> {
					if (m.getName().equals("getReader")) return reader;
					if (m.getName().equals("getWriter")) return writer;
					return null;
				});
	}
}
