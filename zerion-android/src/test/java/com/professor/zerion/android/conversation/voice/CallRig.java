package com.professor.zerion.android.conversation.voice;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;

import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;
import org.zerionproject.app.api.messaging.VoiceSignalHeader;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.app.conversation.voice.VoiceCallConnectionHandler;
import org.zerionproject.app.conversation.voice.VoiceCallConnectionManager;
import org.zerionproject.app.conversation.voice.VoiceCallCrypto;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import androidx.test.core.app.ApplicationProvider;

import static org.robolectric.Shadows.shadowOf;

final class CallRig {

	static final int CONTACT = 7;

	static final class Sent {
		final String type;
		@Nullable
		final String payload;

		Sent(String type, @Nullable String payload) {
			this.type = type;
			this.payload = payload;
		}

		@Override
		public String toString() {
			return type + "(" + payload + ")";
		}
	}

	final Application app = ApplicationProvider.getApplicationContext();
	final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
	final List<String> dialled =
			Collections.synchronizedList(new ArrayList<>());
	final List<String> closedEndpoints =
			Collections.synchronizedList(new ArrayList<>());
	final List<String> createdEndpoints =
			Collections.synchronizedList(new ArrayList<>());
	final AtomicInteger disposed = new AtomicInteger();
	final CountDownLatch releaseDials = new CountDownLatch(1);
	@Nullable
	volatile DuplexTransportConnection dialResult = null;
	@Nullable
	volatile Runnable onDial = null;
	@Nullable
	volatile Runnable onCreateEndpoint = null;
	volatile String endpointOnion =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";
	@Nullable
	volatile VoiceCallConnectionHandler endpointHandler = null;
	final String callId;
	ServiceController<VoiceCallService> controller;

	CallRig(String callId) {
		this.callId = callId;
	}

	VoiceCallCrypto crypto() {
		return ((ZerionApplication) app).getApplicationComponent()
				.voiceCallCrypto();
	}

	VoiceCallConnectionManager realManager() {
		return ((ZerionApplication) app).getApplicationComponent()
				.voiceCallConnectionManager();
	}

	static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	Intent intent(boolean incoming) {
		Intent i = new Intent(app, VoiceCallService.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, incoming);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		return i;
	}

	VoiceCallService ringingIncoming() throws Exception {
		controller = Robolectric.buildService(VoiceCallService.class,
				intent(true)).create().startCommand(0, 1);
		idle();
		VoiceCallService s = controller.get();
		install(s);
		return s;
	}

	VoiceCallService ringingOutgoing() throws Exception {
		controller = Robolectric.buildService(VoiceCallService.class,
				intent(true)).create().startCommand(0, 1);
		idle();
		VoiceCallService s = controller.get();
		call(s, "cancelCallSetupTimeout");
		set(s, "isIncoming", false);
		set(s, "callState", VoiceCallService.CallState.RINGING);
		install(s);
		return s;
	}

	void install(VoiceCallService s) throws Exception {
		set(s, "voiceSignalFactory", signalFactory());
		set(s, "messagingManager", Proxy.newProxyInstance(
				MessagingManager.class.getClassLoader(),
				new Class<?>[] {MessagingManager.class}, (p, m, a) -> null));
		set(s, "conversationGroup", new Group(new GroupId(new byte[32]),
				new ClientId("org.zerionproject.test"), 0, new byte[0]));
		set(s, "connectionManager", connectionManager());
	}

	private VoiceSignalFactory signalFactory() {
		return (VoiceSignalFactory) Proxy.newProxyInstance(
				VoiceSignalFactory.class.getClassLoader(),
				new Class<?>[] {VoiceSignalFactory.class}, (p, m, a) -> {
					String payload = null;
					if (a != null && a.length >= 4 && a[3] instanceof String) {
						payload = (String) a[3];
					}
					sent.add(new Sent(m.getName(), payload));
					return null;
				});
	}

	private VoiceCallConnectionManager connectionManager() {
		VoiceCallConnectionManager real = realManager();
		return (VoiceCallConnectionManager) Proxy.newProxyInstance(
				VoiceCallConnectionManager.class.getClassLoader(),
				new Class<?>[] {VoiceCallConnectionManager.class},
				(p, m, a) -> {
					switch (m.getName()) {
						case "createIncomingEndpoint":
							createdEndpoints.add((String) a[0]);
							endpointHandler =
									(VoiceCallConnectionHandler) a[3];
							Runnable created = onCreateEndpoint;
							if (created != null) created.run();
							return new VoiceCallConnectionManager
									.EndpointInfo(endpointOnion, 80);
						case "connectToRemote":
							dialled.add((String) a[1]);
							Runnable r = onDial;
							if (r != null) r.run();
							DuplexTransportConnection c = dialResult;
							if (c != null) return c;
							releaseDials.await(30, TimeUnit.SECONDS);
							return null;
						case "expectedPeerOnion":
							return m.invoke(real, a);
						case "closeEndpoint":
							closedEndpoints.add((String) a[0]);
							return null;
						default:
							return null;
					}
				});
	}

	DuplexTransportConnection connection() {
		TransportConnectionReader reader = (TransportConnectionReader)
				Proxy.newProxyInstance(
						TransportConnectionReader.class.getClassLoader(),
						new Class<?>[] {TransportConnectionReader.class},
						(p, m, a) -> {
							if (m.getName().equals("dispose")) {
								disposed.incrementAndGet();
								return null;
							}
							if (m.getName().equals("getInputStream")) {
								return new java.io.ByteArrayInputStream(
										new byte[0]);
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
								return null;
							}
							if (m.getName().equals("getOutputStream")) {
								return new java.io.ByteArrayOutputStream();
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

	void peerSends(VoiceCallService s, VoiceSignalType type,
			@Nullable String payload) throws Exception {
		VoiceSignalHeader h = new VoiceSignalHeader(
				new MessageId(new byte[32]), new GroupId(new byte[32]),
				System.currentTimeMillis(), false, type, callId, payload,
				null);
		Method m = VoiceCallService.class.getDeclaredMethod(
				"handleIncomingVoiceSignal", VoiceSignalHeader.class);
		m.setAccessible(true);
		m.invoke(s, h);
		idle();
	}

	List<String> sentTypes() {
		List<String> types = new ArrayList<>();
		synchronized (sent) {
			for (Sent x : sent) types.add(x.type);
		}
		return types;
	}

	@Nullable
	Sent lastSent(String type) {
		synchronized (sent) {
			for (int i = sent.size() - 1; i >= 0; i--) {
				if (sent.get(i).type.equals(type)) return sent.get(i);
			}
		}
		return null;
	}

	static void await(BooleanSupplier condition, long timeoutMs)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) return;
			idle();
			Thread.sleep(20);
		}
	}

	void tearDown() {
		releaseDials.countDown();
		VoiceCallKeyHolder.clear();
		if (controller != null) {
			try {
				controller.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	static Object get(VoiceCallService s, String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(s);
	}

	static void set(VoiceCallService s, String name, Object value)
			throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(s, value);
	}

	static Object call(VoiceCallService s, String name) throws Exception {
		Method m = VoiceCallService.class.getDeclaredMethod(name);
		m.setAccessible(true);
		return m.invoke(s);
	}
}
