package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.test.PermissiveOnionClientAuth;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.sync.ZppConnectionRegistry;
import org.zerionproject.sync.ZppConnectionRunnerImpl;
import org.zerionproject.sync.ZppRecordSink;
import org.zerionproject.sync.ZppSendScheduler;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

public class SideEffectsBeforeAuthenticationTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet m3f;
	private ZwfSessionFactory sessionFactory;
	private SecretKey root;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		Class<?> ratchetClass = Class.forName(
				"org.zerionproject.core.crypto.pcs.PcsRatchetImpl");
		Constructor<?> rc = ratchetClass.getDeclaredConstructors()[0];
		rc.setAccessible(true);
		Object[] args = new Object[rc.getParameterCount()];
		args[0] = crypto;
		ratchet = (PcsRatchet) rc.newInstance(args);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> pc = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		pc.setAccessible(true);
		MlKemProvider mlKem = (MlKemProvider) pc.newInstance(
				crypto.getSecureRandom());
		Class<?> m3fImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> mc = m3fImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		mc.setAccessible(true);
		m3f = (Mode3FullRatchet) mc.newInstance(crypto, mlKem);
		sessionFactory = new ZwfSessionFactory(crypto, m3f);
		byte[] b = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(b);
		root = new SecretKey(b);
	}

	private byte[] aliceStream(ZwfStreamCounter counter, int frames)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ZwfDuplexConnection alice = new ZwfDuplexConnection(2,
				sessionFactory.deriveSession(root, true), counter, crypto,
				ratchet, m3f, XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(new byte[0]), out);
		for (int i = 0; i < frames; i++) alice.sendMessage(ZmmRecord.cover());
		return out.toByteArray();
	}

	@Test
	public void aReplayedStreamIsRefusedBeforeItPublishesRatchetState()
			throws Exception {
		byte[] recorded = aliceStream(new ZwfStreamCounter(new Mem()), 2);
		ZwfStreamCounter bobCounter = new ZwfStreamCounter(new Mem());
		ZwfDuplexConnection first = new ZwfDuplexConnection(1,
				sessionFactory.deriveSession(root, false), bobCounter, crypto,
				ratchet, m3f, XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(recorded), new ByteArrayOutputStream());
		assertNotNull(first.receiveMessage());
		assertTrue(first.isPqReady());

		ZwfDuplexConnection replayed = new ZwfDuplexConnection(1,
				sessionFactory.deriveSession(root, false), bobCounter, crypto,
				ratchet, m3f, XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(recorded), new ByteArrayOutputStream());
		try {
			replayed.receiveMessage();
			fail();
		} catch (FormatException expected) {
		}
		assertNull("the replayed stream published the peer's key",
				replayed.currentMode3FullState().getTheirActivePqPk());
	}

	@Test
	public void aRecognisedTagAloneChangesNothingOutsideTheConnection()
			throws Exception {
		byte[] stream = aliceStream(new ZwfStreamCounter(new Mem()), 1);
		byte[] forged = Arrays.copyOf(stream, stream.length);
		for (int i = 16; i < forged.length; i++) forged[i] ^= 0x5a;

		ZwfStreamCounter bobCounter = new ZwfStreamCounter(new Mem());
		ZwfTagRecogniser recogniser =
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		recogniser.register(1, sessionFactory.deriveRecvTagKey(root, false), 0);
		List<String> effects = Collections.synchronizedList(new ArrayList<>());
		ZtpSessionProvider provider = new ZtpSessionProvider() {
			@Override
			public int recogniseIncoming(byte[] tag) {
				ZwfTagRecogniser.Match m = recogniser.recognise(tag);
				return m == null ? -1 : m.contactId;
			}

			@Override
			public StoredContactSession getStoredSession(int contactId) {
				return new StoredContactSession(
						new SecretKey(root.getBytes().clone()), false);
			}

			@Override
			public void sessionClosed(int contactId) {
			}

			@Override
			public void sessionEstablished(int contactId) {
				effects.add("sessionEstablished");
			}
		};
		ZppConnectionRegistry zppRegistry = new ZppConnectionRegistry() {
			@Override
			public void onConnectionOpened(int contactId,
					ZppSendScheduler scheduler, int maxRecordBytes) {
				effects.add("schedulerOffered");
			}

			@Override
			public void onConnectionClosed(int contactId,
					ZppSendScheduler scheduler) {
			}
		};
		ZppRecordSink sink = new ZppRecordSink() {
			@Override
			public void deliver(int contactId, long sessionId, int type,
					byte[] payload) {
			}

			@Override
			public void onConnected(int contactId, long sessionId) {
				effects.add("sinkConnected");
			}

			@Override
			public void onDisconnected(int contactId, long sessionId) {
			}
		};
		PermissiveOnionClientAuth auth = new PermissiveOnionClientAuth() {
			@Override
			public void inboundViaAuthorizedService(ContactId c) {
				effects.add("authorizedArrival");
			}
		};
		ZtpConnectionHandlerImpl bob = new ZtpConnectionHandlerImpl(
				new ZtpConnectionEstablisher(crypto, ratchet, m3f,
						sessionFactory, bobCounter,
						XSalsa20Poly1305AuthenticatedCipher::new),
				provider, new ZppConnectionRunnerImpl(sink, zppRegistry, 5),
				recordingRegistry(effects), auth);
		bob.handleIncoming(TorConstants.ID, new ByteArrayInputStream(forged),
				new ByteArrayOutputStream(), true);
		assertEquals(Collections.emptyList(), effects);
	}

	private static ConnectionRegistry recordingRegistry(List<String> effects) {
		return new ConnectionRegistry() {
			@Override
			public void registerIncomingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn) {
				effects.add("registered");
			}

			@Override
			public void registerOutgoingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
				effects.add("registered");
			}

			@Override
			public void unregisterConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, boolean incoming,
					boolean exception) {
			}

			@Override
			public void setPriority(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
			}

			@Override
			public Collection<ContactId> getConnectedContacts(TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public Collection<ContactId> getConnectedOrBetterContacts(
					TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public boolean isConnected(ContactId c, TransportId t) {
				return false;
			}

			@Override
			public boolean isConnected(ContactId c) {
				return false;
			}

			@Override
			public boolean registerConnection(PendingContactId p) {
				return true;
			}

			@Override
			public void unregisterConnection(PendingContactId p,
					boolean success) {
			}
		};
	}

	private static final class Mem implements StreamCounterStore {
		private final Map<Long, Long> m = new HashMap<>();

		@Override
		public synchronized long loadHighWater(int c, int d) {
			Long v = m.get((((long) c) << 1) | (d & 1L));
			return v == null ? 0 : v;
		}

		@Override
		public synchronized void storeHighWater(int c, int d, long hw) {
			m.put((((long) c) << 1) | (d & 1L), hw);
		}
	}
}
