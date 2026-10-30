package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.core.test.TestStreamWriter;
import org.jmock.Mockery;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.ALICE_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.BOB_PROOF_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.CONFIRMATION_KEY_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.EXCHANGE_KEY_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.KCI_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.KEY_SEPARATION_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeConstants.ROOT_KEY_LABEL;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HandshakeKeySeparationTest {

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final Transaction txn = new Transaction(null, true);
	private int mockSerial = 0;

	private CryptoComponent crypto;
	private PendingContactFactoryImpl pendingContactFactory;
	private RecordReaderFactory recordReaderFactory;
	private RecordWriterFactory recordWriterFactory;

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
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				Thread.sleep(ms);
			}
		};
		pendingContactFactory = new PendingContactFactoryImpl(crypto, clock);
		recordReaderFactory = (RecordReaderFactory) newInstance(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		recordWriterFactory = (RecordWriterFactory) newInstance(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
	}

	private static Object newInstance(String className) throws Exception {
		Constructor<?> c = Class.forName(className).getDeclaredConstructor();
		c.setAccessible(true);
		return c.newInstance();
	}

	private static final class KeyUse {
		final String method;
		@Nullable
		final String label;
		final byte[] key;

		KeyUse(String method, @Nullable String label, byte[] key) {
			this.method = method;
			this.label = label;
			this.key = key;
		}
	}

	static final class Recorder {
		final List<KeyUse> keysUsed =
				Collections.synchronizedList(new ArrayList<>());
		final List<KeyUse> keysDerived =
				Collections.synchronizedList(new ArrayList<>());
		final List<SecretKey> derivedObjects =
				Collections.synchronizedList(new ArrayList<>());

		@Nullable
		byte[] derived(String label) {
			synchronized (keysDerived) {
				for (KeyUse u : keysDerived) {
					if (label.equals(u.label)) return u.key;
				}
			}
			return null;
		}

		boolean usedAsKey(byte[] key) {
			synchronized (keysUsed) {
				for (KeyUse u : keysUsed) {
					if (Arrays.equals(u.key, key)) return true;
				}
			}
			return false;
		}
	}

	private CryptoComponent recording(Recorder recorder) {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					String label = null;
					if (args != null) {
						for (Object a : args) {
							if (a instanceof String && label == null) {
								label = (String) a;
							}
						}
						for (Object a : args) {
							if (a instanceof SecretKey) {
								recorder.keysUsed.add(new KeyUse(
										method.getName(), label,
										((SecretKey) a).getBytes().clone()));
							}
						}
					}
					Object result;
					try {
						result = method.invoke(crypto, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (result instanceof SecretKey) {
						recorder.keysDerived.add(new KeyUse(method.getName(),
								label, ((SecretKey) result).getBytes().clone()));
						recorder.derivedObjects.add((SecretKey) result);
					}
					return result;
				});
	}

	static final class Party {
		final KeyPair keys;
		final PendingContactId peer;
		final HandshakeManagerImpl manager;
		final Recorder recorder;

		Party(KeyPair keys, PendingContactId peer,
				HandshakeManagerImpl manager, Recorder recorder) {
			this.keys = keys;
			this.peer = peer;
			this.manager = manager;
			this.recorder = recorder;
		}
	}

	static final class Outcome {
		@Nullable
		volatile HandshakeResult first, second;
		@Nullable
		volatile Throwable firstError, secondError;
		final List<Record> fromFirst =
				Collections.synchronizedList(new ArrayList<>());
		final List<Record> fromSecond =
				Collections.synchronizedList(new ArrayList<>());
	}

	private Party party(KeyPair ourKeys, KeyPair theirKeys, String name,
			byte minor) throws Exception {
		PendingContact pending = pendingContactFactory.createPendingContact(
				pendingContactFactory.createHandshakeLink(
						theirKeys.getPublic()), name);
		String prefix = name + "-" + (mockSerial++) + "-";
		TransactionManager db =
				context.mock(TransactionManager.class, prefix + "db");
		IdentityManager identityManager =
				context.mock(IdentityManager.class, prefix + "identity");
		ContactManager contactManager =
				context.mock(ContactManager.class, prefix + "contacts");
		context.checking(new DbExpectations() {{
			allowing(db).transactionWithResult(with(true),
					withDbCallable(txn));
			allowing(contactManager).getPendingContact(txn, pending.getId());
			will(returnValue(pending));
			allowing(contactManager).getPendingContactOurKeys(txn,
					pending.getId());
			will(returnValue(null));
			allowing(identityManager).getHybridHandshakeKeys(txn);
			will(returnValue(ourKeys));
		}});
		Recorder recorder = new Recorder();
		CryptoComponent c = recording(recorder);
		return new Party(ourKeys, pending.getId(),
				new HandshakeManagerImpl(db, identityManager, contactManager,
						new HandshakeCryptoImpl(c), c, pendingContactFactory,
						recordReaderFactory, recordWriterFactory, minor),
				recorder);
	}

	interface Mutator extends BiFunction<Record, Boolean, List<Record>> {
	}

	static final Mutator PASS =
			(r, fromAliceRole) -> Collections.singletonList(r);

	private void relay(InputStream from, OutputStream to, List<Record> seen,
			Mutator mutator, boolean fromAliceRole) {
		try {
			RecordReader r = recordReaderFactory.createRecordReader(from, false);
			RecordWriter w = recordWriterFactory.createRecordWriter(to, false);
			Record rec;
			while ((rec = r.readRecord()) != null) {
				seen.add(rec);
				for (Record out : mutator.apply(rec, fromAliceRole)) {
					w.writeRecord(out);
					w.flush();
				}
			}
		} catch (IOException ignored) {
		} finally {
			try {
				to.close();
			} catch (IOException ignored) {
			}
		}
	}

	private boolean hasAliceRole(Party p, Party other) {
		return compare(crypto.hash(HYBRID_COMMITMENT_LABEL,
						p.keys.getPublic().getEncoded()),
				crypto.hash(HYBRID_COMMITMENT_LABEL,
						other.keys.getPublic().getEncoded())) < 0;
	}

	private Outcome run(Party first, Party second, Mutator mutator)
			throws Exception {
		boolean firstIsAlice = hasAliceRole(first, second);
		PipedOutputStream firstOut = new PipedOutputStream();
		PipedInputStream relayFromFirst = new PipedInputStream(firstOut, 1 << 20);
		PipedOutputStream relayToSecond = new PipedOutputStream();
		PipedInputStream secondIn = new PipedInputStream(relayToSecond, 1 << 20);
		PipedOutputStream secondOut = new PipedOutputStream();
		PipedInputStream relayFromSecond =
				new PipedInputStream(secondOut, 1 << 20);
		PipedOutputStream relayToFirst = new PipedOutputStream();
		PipedInputStream firstIn = new PipedInputStream(relayToFirst, 1 << 20);
		Outcome o = new Outcome();
		Thread t1 = new Thread(() -> {
			try {
				o.first = first.manager.handshake(first.peer, firstIn,
						new TestStreamWriter(firstOut));
			} catch (Throwable t) {
				o.firstError = t;
			}
		});
		Thread t2 = new Thread(() -> {
			try {
				o.second = second.manager.handshake(second.peer, secondIn,
						new TestStreamWriter(secondOut));
			} catch (Throwable t) {
				o.secondError = t;
			}
		});
		Thread r1 = new Thread(() -> relay(relayFromFirst, relayToSecond,
				o.fromFirst, mutator, firstIsAlice));
		Thread r2 = new Thread(() -> relay(relayFromSecond, relayToFirst,
				o.fromSecond, mutator, !firstIsAlice));
		t1.start();
		t2.start();
		r1.start();
		r2.start();
		t1.join(60_000);
		t2.join(60_000);
		closeQuietly(firstOut);
		closeQuietly(secondOut);
		r1.join(10_000);
		r2.join(10_000);
		assertTrue(!t1.isAlive() && !t2.isAlive());
		return o;
	}

	private static void closeQuietly(OutputStream out) {
		try {
			out.close();
		} catch (IOException ignored) {
		}
	}

	Outcome pair(byte firstMinor, byte secondMinor, Mutator mutator,
			Party[] parties) throws Exception {
		KeyPair a = crypto.generateHybridAgreementKeyPair();
		KeyPair b = crypto.generateHybridAgreementKeyPair();
		parties[0] = party(a, b, "first", firstMinor);
		parties[1] = party(b, a, "second", secondMinor);
		return run(parties[0], parties[1], mutator);
	}

	private static void assertPaired(Outcome o) {
		assertNull(String.valueOf(o.firstError), o.firstError);
		assertNull(String.valueOf(o.secondError), o.secondError);
		assertNotNull(o.first);
		assertNotNull(o.second);
		assertArrayEquals(o.first.getRootKey().getBytes(),
				o.second.getRootKey().getBytes());
		assertArrayEquals(o.first.getExchangeKey().getBytes(),
				o.second.getExchangeKey().getBytes());
		assertTrue(o.first.isAlice() != o.second.isAlice());
		assertTrue(o.first.isMode3Capable() && o.second.isMode3Capable());
	}

	@Nullable
	private static byte[] payloadOf(List<Record> records, byte type) {
		synchronized (records) {
			for (Record r : records) {
				if (r.getRecordType() == type) return r.getPayload();
			}
		}
		return null;
	}

	private static byte[] minorSent(List<Record> records) {
		byte[] p = payloadOf(records, RECORD_TYPE_MINOR_VERSION);
		assertNotNull(p);
		return p;
	}

	@Test
	public void theNewMinorVersionIsAnnouncedAndNegotiatedByBothSides() {
		assertEquals(5, PROTOCOL_MINOR_VERSION);
		assertEquals(PROTOCOL_MINOR_VERSION, KEY_SEPARATION_MINOR_VERSION);
		assertTrue(HandshakeManagerImpl.keySeparationNegotiated(5, 5));
		assertTrue(HandshakeManagerImpl.keySeparationNegotiated(5, 9));
		assertFalse(HandshakeManagerImpl.keySeparationNegotiated(5, 4));
		assertFalse(HandshakeManagerImpl.keySeparationNegotiated(4, 5));
		assertFalse(HandshakeManagerImpl.keySeparationNegotiated(4, 4));
	}

	@Test(timeout = 120_000)
	public void currentPeersSeparateTheRootFromTheHandshakeKeys()
			throws Exception {
		Party[] p = new Party[2];
		Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION, PASS,
				p);
		assertPaired(o);
		assertTrue(o.first.isKeySeparated());
		assertTrue(o.second.isKeySeparated());
		assertArrayEquals(new byte[] {5}, minorSent(o.fromFirst));
		assertArrayEquals(new byte[] {5}, minorSent(o.fromSecond));
		byte[] root = o.first.getRootKey().getBytes();
		byte[] exchange = o.first.getExchangeKey().getBytes();
		assertFalse(Arrays.equals(root, exchange));

		boolean firstIsAlice = o.first.isAlice();
		for (int i = 0; i < 2; i++) {
			Party party = p[i];
			boolean alice = (i == 0) == firstIsAlice;
			byte[] confirmation = party.recorder.derived(CONFIRMATION_KEY_LABEL);
			assertNotNull(confirmation);
			assertArrayEquals(root, party.recorder.derived(ROOT_KEY_LABEL));
			assertArrayEquals(exchange,
					party.recorder.derived(EXCHANGE_KEY_LABEL));
			assertFalse(Arrays.equals(root, confirmation));
			assertFalse(Arrays.equals(exchange, confirmation));
			byte[] proof = payloadOf(i == 0 ? o.fromFirst : o.fromSecond,
					RECORD_TYPE_PROOF_OF_OWNERSHIP);
			assertNotNull(proof);
			String label = alice ? ALICE_PROOF_LABEL : BOB_PROOF_LABEL;
			assertArrayEquals("the proof is a MAC under the confirmation key",
					crypto.mac(label, new SecretKey(confirmation.clone())),
					proof);
			assertFalse(Arrays.equals(proof,
					crypto.mac(label, new SecretKey(root.clone()))));
			byte[] cap = payloadOf(i == 0 ? o.fromFirst : o.fromSecond,
					RECORD_TYPE_MODE3_CAPABILITY);
			assertNotNull(cap);
			assertArrayEquals(crypto.mac(
							"org.zerionproject/MODE3_CAPABILITY_MAC",
							new SecretKey(confirmation.clone()), new byte[] {1}),
					Arrays.copyOfRange(cap, 1, cap.length));
		}
	}

	@Test(timeout = 120_000)
	public void theRootIsNeverAKeyOrAWireValueDuringTheHandshake()
			throws Exception {
		Party[] p = new Party[2];
		Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION, PASS,
				p);
		assertPaired(o);
		byte[] root = o.first.getRootKey().getBytes();
		byte[] exchange = o.first.getExchangeKey().getBytes();
		for (Party party : p) {
			assertFalse("the root was used as a key during the handshake",
					party.recorder.usedAsKey(root));
			assertFalse("the exchange key was used during the handshake",
					party.recorder.usedAsKey(exchange));
		}
		List<Record> all = new ArrayList<>(o.fromFirst);
		all.addAll(o.fromSecond);
		for (Record r : all) {
			assertFalse(contains(r.getPayload(), root));
			assertFalse(contains(r.getPayload(), exchange));
		}
	}

	@Test(timeout = 120_000)
	public void withAnEarlierPeerTheRootIsTheHandshakeKeyAsBefore()
			throws Exception {
		Party[] p = new Party[2];
		Outcome o = pair(PROTOCOL_MINOR_VERSION, KCI_MINOR_VERSION, PASS, p);
		assertPaired(o);
		byte[] root = o.first.getRootKey().getBytes();
		assertTrue(p[0].recorder.usedAsKey(root));
		assertNull(p[0].recorder.derived(ROOT_KEY_LABEL));
		assertNull(p[1].recorder.derived(CONFIRMATION_KEY_LABEL));
	}

	@Test(timeout = 120_000)
	public void currentAndEarlierPeersPairInBothRoles() throws Exception {
		byte[][] minors = {
				{PROTOCOL_MINOR_VERSION, KCI_MINOR_VERSION},
				{KCI_MINOR_VERSION, PROTOCOL_MINOR_VERSION},
				{KCI_MINOR_VERSION, KCI_MINOR_VERSION}
		};
		for (byte[] m : minors) {
			for (int run = 0; run < 2; run++) {
				Party[] p = new Party[2];
				Outcome o = pair(m[0], m[1], PASS, p);
				assertPaired(o);
				assertFalse(o.first.isKeySeparated());
				assertFalse(o.second.isKeySeparated());
				assertArrayEquals(o.first.getRootKey().getBytes(),
						o.first.getExchangeKey().getBytes());
				assertArrayEquals(new byte[] {m[0]}, minorSent(o.fromFirst));
				assertArrayEquals(new byte[] {m[1]}, minorSent(o.fromSecond));
				byte[] proof = payloadOf(o.fromFirst,
						RECORD_TYPE_PROOF_OF_OWNERSHIP);
				String label = o.first.isAlice() ? ALICE_PROOF_LABEL
						: BOB_PROOF_LABEL;
				assertArrayEquals("an earlier peer verifies the proof as before",
						crypto.mac(label, new SecretKey(
								o.first.getRootKey().getBytes().clone())),
						proof);
			}
		}
	}

	@Test(timeout = 240_000)
	public void loweringTheAnnouncedMinorVersionInTransitIsDetected()
			throws Exception {
		for (int direction = 0; direction < 2; direction++) {
			boolean fromAlice = direction == 0;
			Mutator downgrade = (r, fromAliceRole) -> {
				if (r.getRecordType() == RECORD_TYPE_MINOR_VERSION
						&& fromAliceRole == fromAlice) {
					return Collections.singletonList(new Record(
							r.getProtocolVersion(), r.getRecordType(),
							new byte[] {KCI_MINOR_VERSION}));
				}
				return Collections.singletonList(r);
			};
			Party[] p = new Party[2];
			Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION,
					downgrade, p);
			assertNull("a downgraded handshake completed", o.first);
			assertNull("a downgraded handshake completed", o.second);
			assertTrue(o.firstError instanceof FormatException
					|| o.secondError instanceof FormatException);
		}
	}

	private static boolean wiped(SecretKey k) {
		for (byte b : k.getBytes()) if (b != 0) return false;
		return true;
	}

	@Test(timeout = 240_000)
	public void everyHandshakeKeyIsWipedWhenTheHandshakeFails()
			throws Exception {
		for (int direction = 0; direction < 2; direction++) {
			boolean fromAlice = direction == 0;
			Mutator downgrade = (r, fromAliceRole) -> {
				if (r.getRecordType() == RECORD_TYPE_MINOR_VERSION
						&& fromAliceRole == fromAlice) {
					return Collections.singletonList(new Record(
							r.getProtocolVersion(), r.getRecordType(),
							new byte[] {KCI_MINOR_VERSION}));
				}
				return Collections.singletonList(r);
			};
			Party[] p = new Party[2];
			Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION,
					downgrade, p);
			assertNull(o.first);
			assertNull(o.second);
			for (Party party : p) {
				assertFalse(party.recorder.derivedObjects.isEmpty());
				for (SecretKey k : party.recorder.derivedObjects) {
					assertTrue("a handshake key survived a failed handshake",
							wiped(k));
				}
			}
		}
	}

	@Test(timeout = 120_000)
	public void theConfirmationKeyIsWipedAfterASuccessfulHandshake()
			throws Exception {
		Party[] p = new Party[2];
		Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION, PASS,
				p);
		assertPaired(o);
		for (Party party : p) {
			int checked = 0;
			synchronized (party.recorder.keysDerived) {
				for (int i = 0; i < party.recorder.keysDerived.size(); i++) {
					String label = party.recorder.keysDerived.get(i).label;
					SecretKey k = party.recorder.derivedObjects.get(i);
					if (CONFIRMATION_KEY_LABEL.equals(label)
							|| "org.zerionproject.core.handshake/HYBRID_MASTER_KEY_KCI_V4"
							.equals(label)) {
						assertTrue(label, wiped(k));
						checked++;
					}
				}
			}
			assertEquals(2, checked);
		}
	}

	@Test(timeout = 240_000)
	public void raisingAnEarlierPeersMinorVersionInTransitIsDetected()
			throws Exception {
		for (int direction = 0; direction < 2; direction++) {
			boolean fromAlice = direction == 0;
			Mutator upgrade = (r, fromAliceRole) -> {
				if (r.getRecordType() == RECORD_TYPE_MINOR_VERSION
						&& fromAliceRole == fromAlice) {
					return Collections.singletonList(new Record(
							r.getProtocolVersion(), r.getRecordType(),
							new byte[] {PROTOCOL_MINOR_VERSION}));
				}
				return Collections.singletonList(r);
			};
			Party[] p = new Party[2];
			Outcome o = pair(KCI_MINOR_VERSION, KCI_MINOR_VERSION, upgrade, p);
			assertNull(o.first);
			assertNull(o.second);
			assertTrue(o.firstError instanceof FormatException
					|| o.secondError instanceof FormatException);
		}
	}

	@Test(timeout = 240_000)
	public void strippingTheMinorVersionIsRefused() throws Exception {
		for (int direction = 0; direction < 2; direction++) {
			boolean fromAlice = direction == 0;
			Mutator strip = (r, fromAliceRole) -> {
				if (r.getRecordType() == RECORD_TYPE_MINOR_VERSION
						&& fromAliceRole == fromAlice) {
					return Collections.emptyList();
				}
				return Collections.singletonList(r);
			};
			Party[] p = new Party[2];
			Outcome o = pair(PROTOCOL_MINOR_VERSION, PROTOCOL_MINOR_VERSION,
					strip, p);
			assertNull(o.first);
			assertNull(o.second);
			assertTrue(o.firstError instanceof FormatException
					|| o.secondError instanceof FormatException);
		}
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
