package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
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
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.contact.HandshakeConstants.PROTOCOL_MAJOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_HYBRID_STATIC_KEY;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_KEM_CIPHERTEXT;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_MINOR_VERSION;
import static org.zerionproject.core.contact.HandshakeRecordTypes.RECORD_TYPE_STATIC_KEM_CIPHERTEXT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs the rendezvous pairing handshake end to end between two real
 * handshake managers over piped streams with the production crypto, and
 * checks the post-quantum authentication property: a peer that holds the
 * committed static public key and its classical X25519 private half but not
 * its static ML-KEM private half (the position of a quantum adversary who
 * has recovered the X25519 key from the link) cannot complete the handshake,
 * and a peer that only speaks the earlier, classically authenticated minor
 * version is refused.
 */
public class HandshakePqAuthenticationTest {

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final Transaction txn = new Transaction(null, true);
	private int mockSerial = 0;

	private CryptoComponent crypto;
	private HandshakeCrypto handshakeCrypto;
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
		handshakeCrypto = new HandshakeCryptoImpl(crypto);
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

	private static final class Party {
		final KeyPair keys;
		final PendingContactId peer;
		final HandshakeManagerImpl manager;

		Party(KeyPair keys, PendingContactId peer,
				HandshakeManagerImpl manager) {
			this.keys = keys;
			this.peer = peer;
			this.manager = manager;
		}
	}

	private static final class Outcome {
		@Nullable
		volatile HandshakeResult first, second;
		@Nullable
		volatile Throwable firstError, secondError;
	}

	private Party party(KeyPair ourKeys, PublicKey theirPublicKey,
			String name) throws Exception {
		PendingContact pending = pendingContactFactory.createPendingContact(
				pendingContactFactory.createHandshakeLink(theirPublicKey),
				name);
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
		return new Party(ourKeys, pending.getId(),
				new HandshakeManagerImpl(db, identityManager, contactManager,
						handshakeCrypto, crypto, pendingContactFactory,
						recordReaderFactory, recordWriterFactory));
	}

	private Outcome run(Party first, Party second) throws Exception {
		PipedOutputStream firstOut = new PipedOutputStream();
		PipedInputStream secondIn = new PipedInputStream(firstOut, 1 << 20);
		PipedOutputStream secondOut = new PipedOutputStream();
		PipedInputStream firstIn = new PipedInputStream(secondOut, 1 << 20);
		Outcome o = new Outcome();
		Thread t1 = new Thread(() -> {
			try {
				o.first = first.manager.handshake(first.peer, firstIn,
						new TestStreamWriter(firstOut));
			} catch (Throwable t) {
				o.firstError = t;
			} finally {
				closeQuietly(firstOut);
			}
		});
		Thread t2 = new Thread(() -> {
			try {
				o.second = second.manager.handshake(second.peer, secondIn,
						new TestStreamWriter(secondOut));
			} catch (Throwable t) {
				o.secondError = t;
			} finally {
				closeQuietly(secondOut);
			}
		});
		t1.start();
		t2.start();
		t1.join(60_000);
		t2.join(60_000);
		assertTrue(!t1.isAlive() && !t2.isAlive());
		return o;
	}

	/**
	 * A real connection is disposed when either side's handshake ends, so a
	 * peer left waiting sees end of stream rather than blocking forever.
	 */
	private static void closeQuietly(OutputStream out) {
		try {
			out.close();
		} catch (IOException ignored) {
		}
	}

	private static KeyPair withoutStaticMlKemPrivateKey(KeyPair real,
			KeyPair unrelated) {
		HybridAgreementPrivateKey realPriv =
				(HybridAgreementPrivateKey) real.getPrivate();
		HybridAgreementPrivateKey otherPriv =
				(HybridAgreementPrivateKey) unrelated.getPrivate();
		return new KeyPair(real.getPublic(), new HybridAgreementPrivateKey(
				realPriv.getX25519PrivateKey(), otherPriv.getMlKemPrivateKey()));
	}

	/**
	 * A2-CRY-01: a peer whose committed static key carries an ML-KEM half
	 * the library rejects is refused as a format error at receipt, on the
	 * handshake thread, instead of ending it with an unchecked exception
	 * and leaving the pending contact registered.
	 */
	@Test(timeout = 120_000)
	public void testPeerWithARejectedMlKemStaticKeyIsRefusedCleanly()
			throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobReal = crypto.generateHybridAgreementKeyPair();
		HybridAgreementPublicKey bobPub =
				(HybridAgreementPublicKey) bobReal.getPublic();
		byte[] bad = new byte[bobPub.getMlKemPublicKey().length];
		Arrays.fill(bad, (byte) 0xFF);
		KeyPair bobUsed = new KeyPair(new HybridAgreementPublicKey(
				bobPub.getX25519PublicKey(), bad), bobReal.getPrivate());
		Party alice = party(aliceKeys, bobUsed.getPublic(), "alice");
		Party bob = party(bobUsed, aliceKeys.getPublic(), "bob");
		Outcome o = run(alice, bob);
		assertNull(o.first);
		assertNull(o.second);
		assertTrue("alice must refuse with a format error, got "
				+ o.firstError, o.firstError instanceof FormatException);
		assertTrue("bob must fail with an I/O error, got " + o.secondError,
				o.secondError instanceof IOException);
	}

	@Test(timeout = 120_000)
	public void testHonestPeersDeriveTheSameMasterKey() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Party alice = party(aliceKeys, bobKeys.getPublic(), "alice");
		Party bob = party(bobKeys, aliceKeys.getPublic(), "bob");
		Outcome o = run(alice, bob);
		assertNull(o.firstError);
		assertNull(o.secondError);
		assertNotNull(o.first);
		assertNotNull(o.second);
		assertArrayEquals(o.first.getMasterKey().getBytes(),
				o.second.getMasterKey().getBytes());
		assertTrue(o.first.isAlice() != o.second.isAlice());
		assertTrue(o.first.isMode3Capable() && o.second.isMode3Capable());
		assertArrayEquals(bobKeys.getPublic().getEncoded(),
				o.first.getTheirStaticHybridPub());
		assertArrayEquals(aliceKeys.getPublic().getEncoded(),
				o.second.getTheirStaticHybridPub());
	}

	@Test(timeout = 240_000)
	public void testPeerWithoutStaticMlKemPrivateKeyCannotPair()
			throws Exception {
		for (int impostor = 0; impostor < 2; impostor++) {
			KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair unrelated = crypto.generateHybridAgreementKeyPair();
			KeyPair aliceUsed = impostor == 0
					? withoutStaticMlKemPrivateKey(aliceKeys, unrelated)
					: aliceKeys;
			KeyPair bobUsed = impostor == 1
					? withoutStaticMlKemPrivateKey(bobKeys, unrelated)
					: bobKeys;
			Party alice = party(aliceUsed, bobKeys.getPublic(), "alice");
			Party bob = party(bobUsed, aliceKeys.getPublic(), "bob");
			Outcome o = run(alice, bob);
			assertNull("impostor " + impostor, o.first);
			assertNull("impostor " + impostor, o.second);
			Throwable honest = impostor == 0 ? o.secondError : o.firstError;
			Throwable cheat = impostor == 0 ? o.firstError : o.secondError;
			assertTrue("honest side must reject with a format error, got "
					+ honest, honest instanceof FormatException);
			assertTrue("impostor must fail too, got " + cheat,
					cheat instanceof IOException);
		}
	}

	@Test(timeout = 120_000)
	public void testPeerSpeakingMinorVersionTwoIsRefused() throws Exception {
		assertMinorVersionRefused((byte) 2);
	}

	/** CRY-07: a peer without the static-ephemeral terms is refused. */
	@Test
	public void testPeerSpeakingMinorVersionThreeIsRefused() throws Exception {
		assertMinorVersionRefused((byte) 3);
	}

	private void assertMinorVersionRefused(byte minor) throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobEphemeral = crypto.generateHybridAgreementKeyPair();
		Party alice = party(aliceKeys, bobKeys.getPublic(), "alice");
		byte[] aliceCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				aliceKeys.getPublic().getEncoded());
		byte[] bobCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				bobKeys.getPublic().getEncoded());
		boolean realPartyIsAlice = compare(aliceCommitment, bobCommitment) < 0;

		PipedOutputStream aliceOut = new PipedOutputStream();
		PipedInputStream bobIn = new PipedInputStream(aliceOut, 1 << 20);
		PipedOutputStream bobOut = new PipedOutputStream();
		PipedInputStream aliceIn = new PipedInputStream(bobOut, 1 << 20);
		Outcome o = new Outcome();
		Thread t = new Thread(() -> {
			try {
				o.first = alice.manager.handshake(alice.peer, aliceIn,
						new TestStreamWriter(aliceOut));
			} catch (Throwable e) {
				o.firstError = e;
			}
		});
		t.start();

		RecordReader r = recordReaderFactory.createRecordReader(bobIn, false);
		RecordWriter w = recordWriterFactory.createRecordWriter(bobOut, false);
		if (realPartyIsAlice) {
			expectRecord(r, RECORD_TYPE_HYBRID_STATIC_KEY);
			write(w, RECORD_TYPE_HYBRID_STATIC_KEY,
					bobKeys.getPublic().getEncoded());
			expectRecord(r, RECORD_TYPE_MINOR_VERSION);
			expectRecord(r, RECORD_TYPE_HYBRID_STATIC_KEY);
			write(w, RECORD_TYPE_MINOR_VERSION, new byte[] {minor});
			write(w, RECORD_TYPE_HYBRID_STATIC_KEY,
					bobEphemeral.getPublic().getEncoded());
		} else {
			write(w, RECORD_TYPE_HYBRID_STATIC_KEY,
					bobKeys.getPublic().getEncoded());
			expectRecord(r, RECORD_TYPE_HYBRID_STATIC_KEY);
			write(w, RECORD_TYPE_MINOR_VERSION, new byte[] {minor});
			write(w, RECORD_TYPE_HYBRID_STATIC_KEY,
					bobEphemeral.getPublic().getEncoded());
		}
		t.join(60_000);
		assertTrue(!t.isAlive());
		assertNull(o.first);
		assertTrue("expected a format error, got " + o.firstError,
				o.firstError instanceof FormatException);
		bobOut.close();
		bobIn.close();
	}

	private static void expectRecord(RecordReader r, byte type)
			throws IOException {
		Record rec = r.readRecord();
		assertNotNull(rec);
		assertEquals(type, rec.getRecordType());
	}

	private static void write(RecordWriter w, byte type, byte[] payload)
			throws IOException {
		w.writeRecord(new Record(PROTOCOL_MAJOR_VERSION, type, payload));
		w.flush();
	}

	@Test
	public void testStaticKemCiphertextRecordTypeIsDistinct() {
		byte[] types = {
				HandshakeRecordTypes.RECORD_TYPE_EPHEMERAL_PUBLIC_KEY,
				HandshakeRecordTypes.RECORD_TYPE_PROOF_OF_OWNERSHIP,
				RECORD_TYPE_MINOR_VERSION,
				RECORD_TYPE_HYBRID_STATIC_KEY,
				HandshakeRecordTypes.RECORD_TYPE_KEM_CIPHERTEXT,
				HandshakeRecordTypes.RECORD_TYPE_MODE3_CAPABILITY,
				RECORD_TYPE_STATIC_KEM_CIPHERTEXT
		};
		for (int i = 0; i < types.length; i++) {
			for (int j = i + 1; j < types.length; j++) {
				assertTrue(types[i] != types[j]);
			}
		}
		assertEquals(4, HandshakeConstants.PROTOCOL_MINOR_VERSION);
		assertEquals(3, HandshakeConstants.PQ_AUTH_MINOR_VERSION);
		assertEquals(4, HandshakeConstants.KCI_MINOR_VERSION);
	}

	/** Rewrites the records of one direction; index counts records seen. */
	private interface Mutator {
		List<Record> apply(Record record, int index);
	}

	private static final Mutator PASS = (r, i) -> java.util.Collections
			.singletonList(r);

	private void relay(InputStream from, OutputStream to, Mutator mutator) {
		try {
			RecordReader r = recordReaderFactory.createRecordReader(from, false);
			RecordWriter w = recordWriterFactory.createRecordWriter(to, false);
			Record rec;
			int i = 0;
			while ((rec = r.readRecord()) != null) {
				for (Record out : mutator.apply(rec, i++)) {
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

	/**
	 * Runs both real parties through a record-level man in the middle. The
	 * first mutator sees the records of the party in the Alice role, the
	 * second the records of the party in the Bob role.
	 */
	private Outcome runThroughRelay(Party alice, Party bob,
			Mutator fromAliceRole, Mutator fromBobRole) throws Exception {
		byte[] aliceCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				alice.keys.getPublic().getEncoded());
		byte[] bobCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
				bob.keys.getPublic().getEncoded());
		boolean aliceHasAliceRole = compare(aliceCommitment, bobCommitment) < 0;
		Mutator fromAlice = aliceHasAliceRole ? fromAliceRole : fromBobRole;
		Mutator fromBob = aliceHasAliceRole ? fromBobRole : fromAliceRole;

		PipedOutputStream aliceOut = new PipedOutputStream();
		PipedInputStream relayInFromAlice =
				new PipedInputStream(aliceOut, 1 << 20);
		PipedOutputStream relayToBob = new PipedOutputStream();
		PipedInputStream bobIn = new PipedInputStream(relayToBob, 1 << 20);
		PipedOutputStream bobOut = new PipedOutputStream();
		PipedInputStream relayInFromBob = new PipedInputStream(bobOut, 1 << 20);
		PipedOutputStream relayToAlice = new PipedOutputStream();
		PipedInputStream aliceIn = new PipedInputStream(relayToAlice, 1 << 20);

		Outcome o = new Outcome();
		Thread ta = new Thread(() -> {
			try {
				o.first = alice.manager.handshake(alice.peer, aliceIn,
						new TestStreamWriter(aliceOut));
			} catch (Throwable t) {
				o.firstError = t;
			}
		});
		Thread tb = new Thread(() -> {
			try {
				o.second = bob.manager.handshake(bob.peer, bobIn,
						new TestStreamWriter(bobOut));
			} catch (Throwable t) {
				o.secondError = t;
			}
		});
		Thread r1 = new Thread(() -> relay(relayInFromAlice, relayToBob,
				fromAlice));
		Thread r2 = new Thread(() -> relay(relayInFromBob, relayToAlice,
				fromBob));
		ta.start();
		tb.start();
		r1.start();
		r2.start();
		ta.join(60_000);
		tb.join(60_000);
		r1.join(10_000);
		r2.join(10_000);
		assertTrue(!ta.isAlive() && !tb.isAlive());
		return o;
	}

	private static Record withPayload(Record r, byte[] payload) {
		return new Record(r.getProtocolVersion(), r.getRecordType(), payload);
	}

	private static Record flipByte(Record r, int position) {
		byte[] p = r.getPayload().clone();
		p[position] ^= 0x01;
		return withPayload(r, p);
	}

	private void assertNoKeyAndFormatErrorOnAtLeastOneSide(Outcome o) {
		assertNull(o.first);
		assertNull(o.second);
		assertTrue("both sides must fail, got " + o.firstError + " / "
				+ o.secondError, o.firstError instanceof IOException
				&& o.secondError instanceof IOException);
		assertTrue("an honest side must reject with a format error, got "
				+ o.firstError + " / " + o.secondError,
				o.firstError instanceof FormatException
						|| o.secondError instanceof FormatException);
	}

	@Test(timeout = 120_000)
	public void testHonestPartiesThroughAPassiveRelayAgree() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Outcome o = runThroughRelay(party(aliceKeys, bobKeys.getPublic(), "a"),
				party(bobKeys, aliceKeys.getPublic(), "b"), PASS, PASS);
		assertNull(o.firstError);
		assertNull(o.secondError);
		assertArrayEquals(o.first.getMasterKey().getBytes(),
				o.second.getMasterKey().getBytes());
	}

	@Test(timeout = 120_000)
	public void testSwappedKemCiphertextsAreRejected() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		AtomicReference<Record> held = new AtomicReference<>();
		Mutator swap = (r, i) -> {
			if (r.getRecordType() == RECORD_TYPE_KEM_CIPHERTEXT) {
				held.set(r);
				return new ArrayList<>();
			}
			if (r.getRecordType() == RECORD_TYPE_STATIC_KEM_CIPHERTEXT
					&& held.get() != null) {
				Record ephemeral = held.getAndSet(null);
				return Arrays.asList(withPayload(ephemeral, r.getPayload()),
						withPayload(r, ephemeral.getPayload()));
			}
			return java.util.Collections.singletonList(r);
		};
		Outcome o = runThroughRelay(party(aliceKeys, bobKeys.getPublic(), "a"),
				party(bobKeys, aliceKeys.getPublic(), "b"), swap, PASS);
		assertNoKeyAndFormatErrorOnAtLeastOneSide(o);
	}

	@Test(timeout = 120_000)
	public void testModifiedTranscriptIsRejected() throws Exception {
		for (int direction = 0; direction < 2; direction++) {
			KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
			int[] staticKeysSeen = {0};
			Mutator flipEphemeral = (r, i) -> {
				if (r.getRecordType() == RECORD_TYPE_HYBRID_STATIC_KEY
						&& ++staticKeysSeen[0] == 2) {
					return java.util.Collections.singletonList(
							flipByte(r, 40));
				}
				return java.util.Collections.singletonList(r);
			};
			Outcome o = runThroughRelay(
					party(aliceKeys, bobKeys.getPublic(), "a"),
					party(bobKeys, aliceKeys.getPublic(), "b"),
					direction == 0 ? flipEphemeral : PASS,
					direction == 0 ? PASS : flipEphemeral);
			assertNoKeyAndFormatErrorOnAtLeastOneSide(o);
		}
	}

	@Test(timeout = 120_000)
	public void testSubstitutedStaticMlKemKeyIsRejected() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair attacker = crypto.generateHybridAgreementKeyPair();
		Mutator substitute = (r, i) -> {
			if (r.getRecordType() == RECORD_TYPE_HYBRID_STATIC_KEY && i == 0) {
				byte[] p = r.getPayload().clone();
				byte[] attackerKey = attacker.getPublic().getEncoded();
				System.arraycopy(attackerKey, 32, p, 32, p.length - 32);
				return java.util.Collections.singletonList(withPayload(r, p));
			}
			return java.util.Collections.singletonList(r);
		};
		Outcome o = runThroughRelay(party(aliceKeys, bobKeys.getPublic(), "a"),
				party(bobKeys, aliceKeys.getPublic(), "b"), substitute, PASS);
		assertNull(o.first);
		assertNull(o.second);
		assertTrue("the receiver must reject the key against the link, got "
				+ o.firstError + " / " + o.secondError,
				o.firstError instanceof FormatException
						|| o.secondError instanceof FormatException);
	}

	@Test(timeout = 120_000)
	public void testReflectedProofFailsRoleSeparation() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		AtomicReference<byte[]> aliceProof = new AtomicReference<>();
		Mutator capture = (r, i) -> {
			if (r.getRecordType() == RECORD_TYPE_PROOF_OF_OWNERSHIP) {
				aliceProof.set(r.getPayload());
			}
			return java.util.Collections.singletonList(r);
		};
		Mutator reflect = (r, i) -> {
			if (r.getRecordType() == RECORD_TYPE_PROOF_OF_OWNERSHIP
					&& aliceProof.get() != null) {
				return java.util.Collections.singletonList(
						withPayload(r, aliceProof.get()));
			}
			return java.util.Collections.singletonList(r);
		};
		Party alice = party(aliceKeys, bobKeys.getPublic(), "a");
		Party bob = party(bobKeys, aliceKeys.getPublic(), "b");
		Outcome o = runThroughRelay(alice, bob, capture, reflect);
		boolean aliceHasAliceRole = compare(
				crypto.hash(HYBRID_COMMITMENT_LABEL,
						aliceKeys.getPublic().getEncoded()),
				crypto.hash(HYBRID_COMMITMENT_LABEL,
						bobKeys.getPublic().getEncoded())) < 0;
		HandshakeResult reflectedTo = aliceHasAliceRole ? o.first : o.second;
		Throwable reflectedError =
				aliceHasAliceRole ? o.firstError : o.secondError;
		assertNull("a proof under the other role must never verify",
				reflectedTo);
		assertTrue("expected a format error, got " + reflectedError,
				reflectedError instanceof FormatException);
	}
}
