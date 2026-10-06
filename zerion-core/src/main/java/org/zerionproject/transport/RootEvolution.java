package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemEncapsulation;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.transport.RootEvolutionRecord.IDENTITY_PART_LENGTH;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_CONFIRM;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_DONE;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_IDENTITY_FIRST;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_IDENTITY_SECOND;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_INIT;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;
import static org.zerionproject.transport.RootEvolutionRecord.MAC_LENGTH;
import static org.zerionproject.transport.RootEvolutionRecord.ML_DSA_PUBLIC_KEY_LENGTH;

@NotThreadSafe
@NotNullByDefault
public class RootEvolution implements ZwfControlHandler {

	private final RootEvolutionManager manager;
	private final RootKeyStore store;
	private final RootEvolutionCrypto evolutionCrypto;
	private final ContactId contactId;
	private final boolean alice;

	@Nullable
	private Sender sender;
	private long offerEpoch = -1;
	@Nullable
	private KeyPair offerX25519;
	@Nullable
	private MlKemKeyPair offerKem;
	private boolean closed = false;
	@Nullable
	private byte[] startPendingId;
	@Nullable
	private byte[] identityFirstHalf;
	private boolean identitySent = false;

	RootEvolution(RootEvolutionManager manager, ContactId contactId,
			boolean alice) {
		this.manager = manager;
		this.store = manager.getStore();
		this.evolutionCrypto = manager.getEvolutionCrypto();
		this.contactId = contactId;
		this.alice = alice;
	}

	@Override
	public void start(Sender sender) {
		this.sender = sender;
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			ContactRootKeys keys = store.load(contactId);
			if (keys != null) {
				try {
					SecretKey pending = keys.getPending();
					if (pending != null && keys.isPendingConfirmed()) {
						startPendingId = evolutionCrypto.pendingId(pending,
								keys.getPendingEpoch());
					}
				} finally {
					wipe(keys);
				}
			}
		} finally {
			lock.unlock();
		}
		sender.sendWhenDue(this::buildHello);
	}

	@Nullable
	private byte[] buildHello() {
		if (closed) return null;
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			ContactRootKeys keys = store.load(contactId);
			if (keys == null) return null;
			try {
				SecretKey pending = keys.getPending();
				byte[] pendingId = pending == null ? new byte[MAC_LENGTH]
						: evolutionCrypto.pendingId(pending,
								keys.getPendingEpoch());
				byte flags = manager.knowsPeerIdentity(contactId) ? 0
						: RootEvolutionRecord.FLAG_WANT_IDENTITY;
				return RootEvolutionRecord.hello(keys.getEpoch(), pendingId,
						flags);
			} finally {
				wipe(keys);
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void onRecord(byte[] payload) {
		if (closed) return;
		RootEvolutionRecord r;
		try {
			r = RootEvolutionRecord.decode(payload);
		} catch (FormatException e) {
			return;
		}
		if (r == null) return;
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			switch (r.kind) {
				case KIND_HELLO:
					onHello(r.epoch, r.a, r.flags);
					break;
				case KIND_INIT:
					if (!alice) onInit(r.epoch, r.a, r.b, r.c);
					break;
				case KIND_RESP:
					if (alice) onResp(r.epoch, r.a, r.b, r.c);
					break;
				case KIND_CONFIRM:
					if (!alice) onConfirm(r.epoch, r.a);
					break;
				case KIND_DONE:
					if (alice) onDone(r.epoch, r.a);
					break;
				case KIND_IDENTITY_FIRST:
					identityFirstHalf = r.a;
					break;
				case KIND_IDENTITY_SECOND:
					onIdentity(r.a);
					break;
				default:
					break;
			}
		} catch (DbException | GeneralSecurityException
				| RuntimeException e) {
			endOffer();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void onPeerStreamAuthenticated(long epoch) {
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			ContactRootKeys keys = store.load(contactId);
			if (keys == null) return;
			try {
				if (keys.getPending() == null) return;
				if (epoch != keys.getPendingEpoch()) return;
				if (store.promote(contactId, keys.getEpoch())) {
					manager.evolved(contactId);
				}
			} finally {
				wipe(keys);
			}
		} catch (DbException e) {
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void close() {
		closed = true;
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			endOffer();
		} finally {
			lock.unlock();
		}
	}

	private void onHello(long peerEpoch, byte[] peerPendingId, byte flags)
			throws DbException {
		if ((flags & RootEvolutionRecord.FLAG_WANT_IDENTITY) != 0) {
			sendIdentity();
		}
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		try {
			onHello(keys, peerEpoch, peerPendingId);
		} finally {
			wipe(keys);
		}
	}

	private void onHello(ContactRootKeys keys, long peerEpoch,
			byte[] peerPendingId) throws DbException {
		if (peerEpoch != keys.getEpoch()) return;
		long e = keys.getEpoch();
		boolean peerHasPending = !isZero(peerPendingId);
		SecretKey pending = keys.getPending();
		if (pending != null) {
			if (peerHasPending && evolutionCrypto.verifyPendingId(
					peerPendingId, pending, e + 1)) {
				if (!alice && store.promote(contactId, e)) {
					manager.evolved(contactId);
					send(RootEvolutionRecord.done(e,
							evolutionCrypto.responderDone(pending, e + 1)));
				}
				return;
			}
			if (!alice || !keys.isPendingConfirmed()) return;
			byte[] startId = startPendingId;
			if (startId == null || !MessageDigest.isEqual(startId,
					evolutionCrypto.pendingId(pending, e + 1))) {
				return;
			}
			if (!store.discardPending(contactId, e)) return;
			startPendingId = null;
		}
		if (alice && offerEpoch < 0 && manager.mayOffer(contactId, this)) {
			offer(e, peerHasPending ? peerPendingId : new byte[MAC_LENGTH]);
		}
	}

	private void offer(long e, byte[] replaces) {
		KeyPair x25519 = manager.getCrypto().generateAgreementKeyPair();
		MlKemKeyPair kem = manager.getMlKem().generateKeyPair();
		offerEpoch = e;
		offerX25519 = x25519;
		offerKem = kem;
		manager.offerStarted(contactId, this);
		send(RootEvolutionRecord.init(e, replaces,
				x25519.getPublic().getEncoded(), kem.getEncapsulationKey()));
	}

	private void onInit(long e, byte[] replaces, byte[] initiatorX25519,
			byte[] ek) throws DbException, GeneralSecurityException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		try {
			if (keys.getEpoch() != e) return;
			SecretKey pending = keys.getPending();
			if (pending != null) {
				if (keys.isPendingConfirmed()) return;
				if (!evolutionCrypto.verifyPendingId(replaces, pending, e + 1)) {
					return;
				}
			}
			if (!manager.mayAnswer(contactId)) return;
			answer(keys, e, initiatorX25519, ek);
		} finally {
			wipe(keys);
		}
	}

	private void answer(ContactRootKeys keys, long e, byte[] initiatorX25519,
			byte[] ek) throws DbException, GeneralSecurityException {
		if (!manager.getMlKem().isValidEncapsulationKey(ek)) return;
		PublicKey theirX25519 = manager.getCrypto().getAgreementKeyParser()
				.parsePublicKey(initiatorX25519);
		KeyPair ourX25519 = manager.getCrypto().generateAgreementKeyPair();
		MlKemEncapsulation enc = manager.getMlKem().encapsulate(ek);
		byte[] responderX25519 = ourX25519.getPublic().getEncoded();
		SecretKey dh = null;
		SecretKey next = null;
		try {
			byte[] transcript = evolutionCrypto.transcriptHash(e,
					initiatorX25519, ek, responderX25519,
					enc.getCiphertext());
			dh = evolutionCrypto.agree(theirX25519, ourX25519,
					initiatorX25519, responderX25519);
			next = evolutionCrypto.nextRoot(keys.getCurrent(), e,
					enc.getSharedSecret(), dh, transcript);
			if (!store.storePending(contactId, e, next, false)) return;
			send(RootEvolutionRecord.resp(e, responderX25519,
					enc.getCiphertext(),
					evolutionCrypto.responderConfirm(next, e + 1)));
		} finally {
			Arrays.fill(enc.getSharedSecret(), (byte) 0);
			Arrays.fill(ourX25519.getPrivate().getEncoded(), (byte) 0);
			if (dh != null) dh.clear();
			if (next != null) next.clear();
		}
	}

	private void onResp(long e, byte[] responderX25519, byte[] ciphertext,
			byte[] confirm) throws DbException, GeneralSecurityException {
		KeyPair x25519 = offerX25519;
		MlKemKeyPair kem = offerKem;
		if (x25519 == null || kem == null || e != offerEpoch) return;
		ContactRootKeys keys = store.load(contactId);
		if (keys == null || keys.getEpoch() != e || keys.getPending() != null) {
			if (keys != null) wipe(keys);
			endOffer();
			return;
		}
		PublicKey theirX25519 = manager.getCrypto().getAgreementKeyParser()
				.parsePublicKey(responderX25519);
		byte[] initiatorX25519 = x25519.getPublic().getEncoded();
		byte[] kemSecret = null;
		SecretKey dh = null;
		SecretKey next = null;
		try {
			kemSecret = manager.getMlKem().decapsulate(
					kem.getDecapsulationKey(), ciphertext);
			byte[] transcript = evolutionCrypto.transcriptHash(e,
					initiatorX25519, kem.getEncapsulationKey(),
					responderX25519, ciphertext);
			dh = evolutionCrypto.agree(theirX25519, x25519, initiatorX25519,
					responderX25519);
			next = evolutionCrypto.nextRoot(keys.getCurrent(), e, kemSecret,
					dh, transcript);
			if (!evolutionCrypto.verifyResponderConfirm(confirm, next, e + 1))
				return;
			if (!store.storePending(contactId, e, next, true)) return;
			manager.evolved(contactId);
			send(RootEvolutionRecord.confirm(e,
					evolutionCrypto.initiatorConfirm(next, e + 1)));
		} finally {
			if (kemSecret != null) Arrays.fill(kemSecret, (byte) 0);
			if (dh != null) dh.clear();
			if (next != null) next.clear();
			wipe(keys);
			endOffer();
		}
	}

	private void onConfirm(long e, byte[] mac) throws DbException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		SecretKey pending = keys.getPending();
		try {
			if (keys.getEpoch() != e || pending == null) return;
			if (!evolutionCrypto.verifyInitiatorConfirm(mac, pending, e + 1))
				return;
			if (!store.promote(contactId, e)) return;
			manager.evolved(contactId);
			send(RootEvolutionRecord.done(e,
					evolutionCrypto.responderDone(pending, e + 1)));
		} finally {
			wipe(keys);
		}
	}

	private void onDone(long e, byte[] mac) throws DbException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		SecretKey pending = keys.getPending();
		try {
			if (keys.getEpoch() != e || pending == null) return;
			if (!evolutionCrypto.verifyResponderDone(mac, pending, e + 1))
				return;
			if (store.promote(contactId, e)) manager.evolved(contactId);
		} finally {
			wipe(keys);
		}
	}

	private void sendIdentity() {
		if (identitySent) return;
		identitySent = true;
		byte[][] records = manager.identityRecords();
		if (records == null) return;
		for (byte[] r : records) send(r);
	}

	private void onIdentity(byte[] secondHalf) {
		byte[] first = identityFirstHalf;
		identityFirstHalf = null;
		if (first == null) return;
		byte[] all = new byte[IDENTITY_PART_LENGTH * 2];
		System.arraycopy(first, 0, all, 0, IDENTITY_PART_LENGTH);
		System.arraycopy(secondHalf, 0, all, IDENTITY_PART_LENGTH,
				IDENTITY_PART_LENGTH);
		byte[] key = Arrays.copyOf(all, ML_DSA_PUBLIC_KEY_LENGTH);
		byte[] signature = Arrays.copyOfRange(all, ML_DSA_PUBLIC_KEY_LENGTH,
				all.length);
		manager.learnPeerIdentity(contactId, key, signature);
	}

	private static void wipe(ContactRootKeys keys) {
		keys.getCurrent().clear();
		SecretKey pending = keys.getPending();
		if (pending != null) pending.clear();
	}

	private void send(byte[] payload) {
		Sender s = sender;
		if (s != null && !closed) s.send(payload);
	}

	private void endOffer() {
		if (offerX25519 != null) {
			Arrays.fill(offerX25519.getPrivate().getEncoded(), (byte) 0);
			offerX25519 = null;
		}
		if (offerKem != null) {
			Arrays.fill(offerKem.getDecapsulationKey(), (byte) 0);
			offerKem = null;
		}
		offerEpoch = -1;
		manager.offerEnded(contactId, this);
	}

	private static boolean isZero(byte[] b) {
		int acc = 0;
		for (byte x : b) acc |= x;
		return acc == 0;
	}
}
