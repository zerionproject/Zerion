package org.zerionproject.transport;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import static org.zerionproject.core.test.TestUtils.getContact;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZtpContactIdReuseTest extends BrambleMockTestCase {

	private static final int CONTACT = 2;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final LifecycleManager lifecycleManager =
			context.mock(LifecycleManager.class);
	private final MemStore store = new MemStore();
	private final ZwfStreamCounter counter = new ZwfStreamCounter(store);

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private ZwfSessionFactory sessionFactory;
	private SecretKey rootKey;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImpl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImpl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		ratchet = new PcsRatchetImpl(crypto);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> pc = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		pc.setAccessible(true);
		MlKemProvider mlKem = (MlKemProvider) pc.newInstance(
				crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> rc = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		rc.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) rc.newInstance(crypto, mlKem);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
		byte[] b = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(b);
		rootKey = new SecretKey(b);
	}

	@Test
	public void theGenerationIsReadBeforeTheRootKey() throws Exception {
		Transaction txn = new Transaction(null, true);
		Contact contact = getContact();
		context.checking(new DbExpectations() {{
			allowing(lifecycleManager).registerService(
					with(any(Service.class)));
			oneOf(db).transactionWithNullableResult(with(true),
					withNullableDbCallable(txn));
			oneOf(contactManager).getContact(txn, new ContactId(CONTACT));
			will(returnValue(contact));
		}});
		PcsStateManager removedWhileLoading = new PcsStateManager(db,
				lifecycleManager) {
			@Override
			public PcsSessionState loadSendState(ContactId c) {
				counter.retireContact(c.getInt());
				return new PcsSessionState(rootKey, 0, 0, rootKey, null);
			}
		};
		ZtpSessionProviderImpl provider = new ZtpSessionProviderImpl(
				(ZwfTagRecogniser) null, contactManager, removedWhileLoading, sessionFactory, counter,
				db, null, Runnable::run, null);
		StoredContactSession stored = provider.getStoredSession(CONTACT);
		assertNotNull(stored);
		assertEquals(0, stored.getGeneration());
		assertEquals(1, counter.generation(CONTACT));
		ZwfDuplexConnection connection = connection(stored.getGeneration());
		try {
			connection.sendMessage(new byte[1]);
			fail("a connection holding the removed contact's key sent");
		} catch (IOException expected) {
		}
		assertTrue(store.m.isEmpty());
	}

	@Test
	public void aConnectionOpenedBeforeTheRemovalCannotOpenItsSendStream()
			throws Exception {
		ZwfDuplexConnection early = connection(counter.generation(CONTACT));
		assertEquals(1, counter.allocateSendStreamId(CONTACT));
		counter.retireContact(CONTACT);
		store.m.clear();
		try {
			early.sendMessage(new byte[1]);
			fail("a connection opened before the removal sent");
		} catch (IOException expected) {
		}
		assertTrue(store.m.isEmpty());
		ZwfDuplexConnection next = connection(counter.generation(CONTACT));
		next.sendMessage(new byte[1]);
		assertEquals("the next contact's first stream was id 1", 2,
				counter.allocateSendStreamId(CONTACT));
	}

	private ZwfDuplexConnection connection(long generation) {
		ZwfSession session = sessionFactory.deriveSession(rootKey, true);
		return new ZwfDuplexConnection(CONTACT, generation, session, counter,
				crypto, ratchet, mode3FullRatchet,
				XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream());
	}

	private static class MemStore implements StreamCounterStore {
		final Map<Long, Long> m = new HashMap<>();

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
