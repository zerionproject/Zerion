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
import java.util.concurrent.ScheduledFuture;
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
import static org.zerionproject.transport.RootEvolutionRecord.VERSION;
import static org.zerionproject.transport.RootEvolutionRecord.VERSION_SEPARATED;

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
	private long offeredAt;
	private byte offerVersion = VERSION;
	private boolean peerAcceptsSeparated = false;
	@Nullable
	private KeyPair offerX25519;
	@Nullable
	private MlKemKeyPair offerKem;
	private volatile boolean closed = false;
	@Nullable
	private byte[] startPendingId;
	@Nullable
	private byte[] identityFirstHalf;
	private boolean identitySent = false;
	private volatile boolean holding = false;
	private boolean expected = false;
	private boolean awaitingHello = false;
	private boolean peerEnforces = false;
	private boolean peerPaused = false;
	private boolean selfPaused = false;
	private boolean helloSeen = false;
	@Nullable
	private ScheduledFuture<?> deadline;
	private int deadlineRound;
	@Nullable
	private ScheduledFuture<?> live;
	@Nullable
	private ScheduledFuture<?> nudge;

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
			long epoch = 0;
			if (keys != null) {
				try {
					epoch = keys.getEpoch();
					SecretKey pending = keys.getPending();
					if (pending != null && keys.isPendingConfirmed()) {
						startPendingId = pendingId(keys);
					}
				} finally {
					wipe(keys);
				}
			}
			manager.watch(contactId, this);
			if (keys != null && manager.isEnforcing()) {
				selfPaused = paused();
				peerEnforces = store.peerEnforces(contactId);
				boolean speaks = epoch > 0 || peerEnforces
						|| store.peerEvolves(contactId);
				if (speaks && !selfPaused) {
					awaitingHello = true;
					holding = true;
					armDeadline();
				}
				scheduleLive(manager.isDue(contactId)
						? manager.liveIntervalMs()
						: manager.untilLiveEvolution(contactId));
			}
		} finally {
			lock.unlock();
		}
		sender.sendWhenDue(this::buildHello);
	}

	@Override
	public boolean holdsUserRecords() {
		return holding && !closed;
	}

	private boolean paused() {
		return store.isPaused(manager.now()) || store.isSnapshotting();
	}

	private void armDeadline() {
		if (deadline != null) return;
		int round = ++deadlineRound;
		deadline = manager.after(manager.deadlineMs(),
				() -> onDeadline(round));
	}

	private void disarmDeadline() {
		ScheduledFuture<?> d = deadline;
		if (d != null) d.cancel(false);
		deadline = null;
		deadlineRound++;
	}

	private void release() {
		boolean held = holding;
		expected = false;
		awaitingHello = false;
		holding = false;
		disarmDeadline();
		if (!closed) manager.healthy(contactId);
		ScheduledFuture<?> n = nudge;
		if (n != null) n.cancel(false);
		nudge = null;
		Sender s = sender;
		if (held && s != null && !closed) s.released();
	}

	void onEvolved() {
		release();
		if (!closed && manager.isEnforcing()) {
			scheduleLive(manager.liveIntervalMs());
		}
	}

	private void scheduleLive(long delayMs) {
		ScheduledFuture<?> old = live;
		if (old != null) old.cancel(false);
		live = closed ? null : manager.after(delayMs, this::onLive);
	}

	private void startNudging() {
		if (alice || nudge != null || closed) return;
		nudge = manager.after(manager.nudgeMs(), this::onNudge);
	}

	private void onNudge() {
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			nudge = null;
			if (closed || !expected) return;
			notePause();
			if (selfPaused) {
				release();
				return;
			}
			byte[] hello = buildHello(true);
			if (hello != null) send(hello);
			startNudging();
		} finally {
			lock.unlock();
		}
	}

	private void onDeadline(int round) {
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			if (closed || round != deadlineRound
					|| !(expected || awaitingHello)) {
				return;
			}
			if (!manager.hasOtherConnection(contactId, this)) {
				manager.stalled(contactId);
			}
		} finally {
			lock.unlock();
		}
		Sender s = sender;
		if (s != null) s.close();
	}

	private void onLive() {
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			if (closed) return;
			scheduleLive(manager.liveIntervalMs());
			if (!helloSeen) return;
			if (paused()) {
				if (!selfPaused) {
					selfPaused = true;
					byte[] hello = buildHello(false);
					if (hello != null) send(hello);
				}
				return;
			}
			selfPaused = false;
			if (peerPaused) return;
			if (alice) offerOnLiveConnection();
			if (!peerEnforces) return;
			expected = true;
			armDeadline();
			if (!alice) {
				byte[] hello = buildHello(true);
				if (hello != null) send(hello);
				startNudging();
			}
		} catch (RuntimeException e) {
			endOffer();
		} finally {
			lock.unlock();
		}
	}

	private void offerOnLiveConnection() {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		try {
			if (keys.getPending() != null) return;
			if (offerEpoch >= 0) {
				if (System.nanoTime() - offeredAt
						< manager.deadlineMs() * 1_000_000L) {
					return;
				}
				endOffer();
			}
			if (!manager.mayOffer(contactId, this, true)) return;
			offer(keys.getEpoch(), new byte[MAC_LENGTH]);
		} finally {
			wipe(keys);
		}
	}

	@Nullable
	private byte[] buildHello() {
		return buildHello(false);
	}

	@Nullable
	private byte[] buildHello(boolean dueNow) {
		if (closed) return null;
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			ContactRootKeys keys = store.load(contactId);
			if (keys == null) return null;
			try {
				SecretKey pending = keys.getPending();
				byte[] pendingId = pending == null ? new byte[MAC_LENGTH]
						: pendingId(keys);
				byte flags = manager.knowsPeerIdentity(contactId) ? 0
						: RootEvolutionRecord.FLAG_WANT_IDENTITY;
				if (manager.isSeparatedEvolution()) {
					flags |= RootEvolutionRecord.FLAG_SEPARATED_EVOLUTION;
				}
				if (manager.isEnforcing()) {
					flags |= RootEvolutionRecord.FLAG_ENFORCES_EVOLUTION;
					if (selfPaused || paused()) {
						flags |= RootEvolutionRecord.FLAG_EVOLUTION_PAUSED;
					} else if (dueNow || expected
							|| manager.isDue(contactId)) {
						flags |= RootEvolutionRecord.FLAG_EVOLUTION_DUE;
					}
				}
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
		if (r.version == VERSION_SEPARATED
				&& !manager.isSeparatedEvolution()) {
			return;
		}
		ReentrantLock lock = manager.lock(contactId);
		lock.lock();
		try {
			switch (r.kind) {
				case KIND_HELLO:
					onHello(r.epoch, r.a, r.flags);
					break;
				case KIND_INIT:
					if (!alice) onInit(r.version, r.epoch, r.a, r.b, r.c);
					break;
				case KIND_RESP:
					if (alice) onResp(r.version, r.epoch, r.a, r.b, r.c);
					break;
				case KIND_CONFIRM:
					if (!alice) onConfirm(r.version, r.epoch, r.a);
					break;
				case KIND_DONE:
					if (alice) onDone(r.version, r.epoch, r.a);
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
			if ((expected || awaitingHello)
					&& !manager.hasOtherConnection(contactId, this)) {
				manager.stalled(contactId);
			}
			manager.unwatch(contactId, this);
			release();
			ScheduledFuture<?> l = live;
			if (l != null) l.cancel(false);
			live = null;
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
		peerAcceptsSeparated =
				(flags & RootEvolutionRecord.FLAG_SEPARATED_EVOLUTION) != 0;
		boolean first = !helloSeen;
		helloSeen = true;
		ContactRootKeys keys = store.load(contactId);
		boolean peerDue = false;
		if (manager.isEnforcing()) {
			if (first && !store.peerEvolves(contactId)) {
				store.setPeerEvolves(contactId);
			}
			if (!peerEnforces && (flags
					& RootEvolutionRecord.FLAG_ENFORCES_EVOLUTION) != 0) {
				peerEnforces = true;
				store.setPeerEnforces(contactId, true);
			}
			peerPaused = peerEnforces && (flags
					& RootEvolutionRecord.FLAG_EVOLUTION_PAUSED) != 0;
			peerDue = peerEnforces && !peerPaused && keys != null
					&& peerEpoch == keys.getEpoch() && (flags
					& RootEvolutionRecord.FLAG_EVOLUTION_DUE) != 0;
			awaitingHello = false;
			notePause();
			if (!peerEnforces || peerPaused || selfPaused) {
				release();
			} else if (peerDue || manager.isDue(contactId)) {
				expected = true;
				if (first) {
					holding = true;
					disarmDeadline();
				}
				armDeadline();
				startNudging();
			} else if (first) {
				release();
			}
		}
		if (keys == null) return;
		try {
			onHello(keys, peerEpoch, peerPendingId, peerDue, first);
		} finally {
			wipe(keys);
		}
	}

	private void notePause() {
		boolean pausedNow = paused();
		if (pausedNow && !selfPaused) {
			selfPaused = true;
			byte[] hello = buildHello(false);
			if (hello != null) send(hello);
		} else if (!pausedNow) {
			selfPaused = false;
		}
	}

	private void resendConfirm(ContactRootKeys keys, long e) {
		SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
		try {
			send(RootEvolutionRecord.confirm(
					RootEvolutionCrypto.pendingVersion(keys), e,
					evolutionCrypto.initiatorConfirmWith(confirmKey, e + 1)));
		} finally {
			confirmKey.clear();
		}
	}

	private void onHello(ContactRootKeys keys, long peerEpoch,
			byte[] peerPendingId, boolean peerDue, boolean first)
			throws DbException {
		if (peerEpoch != keys.getEpoch()) return;
		long e = keys.getEpoch();
		boolean peerHasPending = !isZero(peerPendingId);
		SecretKey pending = keys.getPending();
		if (pending != null) {
			if (peerHasPending && verifyPendingId(keys, peerPendingId)) {
				if (!alice && store.promote(contactId, e)) {
					manager.evolved(contactId);
					sendDone(keys, e);
				} else if (alice && keys.isPendingConfirmed()) {
					resendConfirm(keys, e);
				}
				return;
			}
			if (!alice || !keys.isPendingConfirmed()) return;
			byte[] startId = startPendingId;
			if (startId == null
					|| !MessageDigest.isEqual(startId, pendingId(keys))) {
				return;
			}
			if (!store.discardPending(contactId, e)) return;
			startPendingId = null;
		}
		if (alice && !first && peerDue && offerEpoch >= 0
				&& System.nanoTime() - offeredAt
				>= manager.nudgeMs() * 3 * 1_000_000L) {
			endOffer();
		}
		if (alice && offerEpoch < 0
				&& manager.mayOffer(contactId, this, peerDue || expected)) {
			offer(e, peerHasPending ? peerPendingId : new byte[MAC_LENGTH]);
		}
	}

	private void offer(long e, byte[] replaces) {
		KeyPair x25519 = manager.getCrypto().generateAgreementKeyPair();
		MlKemKeyPair kem = manager.getMlKem().generateKeyPair();
		byte version = manager.isSeparatedEvolution() && peerAcceptsSeparated
				? VERSION_SEPARATED : VERSION;
		offerEpoch = e;
		offeredAt = System.nanoTime();
		offerVersion = version;
		offerX25519 = x25519;
		offerKem = kem;
		manager.offerStarted(contactId, this);
		send(RootEvolutionRecord.init(version, e, replaces,
				x25519.getPublic().getEncoded(), kem.getEncapsulationKey()));
	}

	private void onInit(byte version, long e, byte[] replaces,
			byte[] initiatorX25519, byte[] ek)
			throws DbException, GeneralSecurityException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		try {
			peerPaused = false;
			if (keys.getEpoch() != e) return;
			SecretKey pending = keys.getPending();
			if (pending != null) {
				if (keys.isPendingConfirmed()) return;
				if (!verifyPendingId(keys, replaces)) return;
			}
			if (!manager.mayAnswer(contactId)) {
				if (manager.isEnforcing()) notePause();
				return;
			}
			answer(keys, version, e, initiatorX25519, ek);
		} finally {
			wipe(keys);
		}
	}

	private void answer(ContactRootKeys keys, byte version, long e,
			byte[] initiatorX25519, byte[] ek)
			throws DbException, GeneralSecurityException {
		if (!manager.getMlKem().isValidEncapsulationKey(ek)) return;
		PublicKey theirX25519 = manager.getCrypto().getAgreementKeyParser()
				.parsePublicKey(initiatorX25519);
		KeyPair ourX25519 = manager.getCrypto().generateAgreementKeyPair();
		MlKemEncapsulation enc = manager.getMlKem().encapsulate(ek);
		byte[] responderX25519 = ourX25519.getPublic().getEncoded();
		SecretKey dh = null;
		RootEvolutionCrypto.EvolvedKeys next = null;
		try {
			byte[] transcript = evolutionCrypto.transcriptHash(version, e,
					initiatorX25519, ek, responderX25519,
					enc.getCiphertext());
			dh = evolutionCrypto.agree(theirX25519, ourX25519,
					initiatorX25519, responderX25519);
			next = evolutionCrypto.evolve(version, keys.getCurrent(), e,
					enc.getSharedSecret(), dh, transcript);
			if (!store.storePending(contactId, e, next.root,
					next.isSeparated() ? next.confirmKey : null, false)) {
				return;
			}
			send(RootEvolutionRecord.resp(version, e, responderX25519,
					enc.getCiphertext(),
					evolutionCrypto.responderConfirmWith(next.confirmKey,
							e + 1)));
		} finally {
			Arrays.fill(enc.getSharedSecret(), (byte) 0);
			Arrays.fill(ourX25519.getPrivate().getEncoded(), (byte) 0);
			if (dh != null) dh.clear();
			if (next != null) next.clear();
		}
	}

	private void onResp(byte version, long e, byte[] responderX25519,
			byte[] ciphertext, byte[] confirm)
			throws DbException, GeneralSecurityException {
		KeyPair x25519 = offerX25519;
		MlKemKeyPair kem = offerKem;
		if (x25519 == null || kem == null || e != offerEpoch
				|| version != offerVersion) {
			return;
		}
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
		RootEvolutionCrypto.EvolvedKeys next = null;
		try {
			kemSecret = manager.getMlKem().decapsulate(
					kem.getDecapsulationKey(), ciphertext);
			byte[] transcript = evolutionCrypto.transcriptHash(version, e,
					initiatorX25519, kem.getEncapsulationKey(),
					responderX25519, ciphertext);
			dh = evolutionCrypto.agree(theirX25519, x25519, initiatorX25519,
					responderX25519);
			next = evolutionCrypto.evolve(version, keys.getCurrent(), e,
					kemSecret, dh, transcript);
			if (!evolutionCrypto.verifyResponderConfirmWith(confirm,
					next.confirmKey, e + 1)) {
				return;
			}
			if (!store.storePending(contactId, e, next.root,
					next.isSeparated() ? next.confirmKey : null, true)) {
				return;
			}
			manager.proved(contactId);
			send(RootEvolutionRecord.confirm(version, e,
					evolutionCrypto.initiatorConfirmWith(next.confirmKey,
							e + 1)));
		} finally {
			if (kemSecret != null) Arrays.fill(kemSecret, (byte) 0);
			if (dh != null) dh.clear();
			if (next != null) next.clear();
			wipe(keys);
			endOffer();
		}
	}

	private void onConfirm(byte version, long e, byte[] mac)
			throws DbException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		SecretKey pending = keys.getPending();
		try {
			if (keys.getEpoch() != e || pending == null) return;
			if (RootEvolutionCrypto.pendingVersion(keys) != version) return;
			SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
			try {
				if (!evolutionCrypto.verifyInitiatorConfirmWith(mac,
						confirmKey, e + 1)) {
					return;
				}
			} finally {
				confirmKey.clear();
			}
			if (!store.promote(contactId, e)) return;
			manager.evolved(contactId);
			sendDone(keys, e);
		} finally {
			wipe(keys);
		}
	}

	private void onDone(byte version, long e, byte[] mac) throws DbException {
		ContactRootKeys keys = store.load(contactId);
		if (keys == null) return;
		SecretKey pending = keys.getPending();
		try {
			if (keys.getEpoch() != e || pending == null) return;
			if (RootEvolutionCrypto.pendingVersion(keys) != version) return;
			SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
			try {
				if (!evolutionCrypto.verifyResponderDoneWith(mac, confirmKey,
						e + 1)) {
					return;
				}
			} finally {
				confirmKey.clear();
			}
			if (store.promote(contactId, e)) manager.evolved(contactId);
		} finally {
			wipe(keys);
		}
	}

	private void sendDone(ContactRootKeys keys, long e) {
		SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
		try {
			send(RootEvolutionRecord.done(
					RootEvolutionCrypto.pendingVersion(keys), e,
					evolutionCrypto.responderDoneWith(confirmKey, e + 1)));
		} finally {
			confirmKey.clear();
		}
	}

	private byte[] pendingId(ContactRootKeys keys) {
		SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
		try {
			return evolutionCrypto.pendingIdWith(confirmKey,
					keys.getPendingEpoch());
		} finally {
			confirmKey.clear();
		}
	}

	private boolean verifyPendingId(ContactRootKeys keys, byte[] id) {
		SecretKey confirmKey = evolutionCrypto.pendingConfirmKey(keys);
		try {
			return evolutionCrypto.verifyPendingIdWith(id, confirmKey,
					keys.getPendingEpoch());
		} finally {
			confirmKey.clear();
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
		SecretKey pendingConfirmKey = keys.getPendingConfirmKey();
		if (pendingConfirmKey != null) pendingConfirmKey.clear();
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
		offerVersion = VERSION;
		manager.offerEnded(contactId, this);
	}

	private static boolean isZero(byte[] b) {
		int acc = 0;
		for (byte x : b) acc |= x;
		return acc == 0;
	}
}
