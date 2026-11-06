package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@ThreadSafe
@Singleton
@NotNullByDefault
public class RootEvolutionManager {

	public static final long MIN_EVOLUTION_INTERVAL_MS = 10 * 60_000L;

	public static final long LIVE_EVOLUTION_INTERVAL_MS = 60 * 60_000L;

	public static final long EVOLUTION_DEADLINE_MS = 30_000L;

	public static final long EVOLUTION_NUDGE_MS = 5_000L;

	public static final long STALL_MEMORY_MS = 60_000L;

	public static final int MAX_ANSWERS_PER_INTERVAL = 6;
	public static final long ANSWER_INTERVAL_MS = 10 * 60_000L;

	public static final int PENDING_DIAL_FALLBACK_AFTER = 2;

	public static final int OUT_OF_SYNC_AFTER = 6;

	public interface PeerIdentity {

		boolean knowsPeerKey(ContactId c);

		@Nullable
		byte[][] ownKeyAndSignature();

		void learnPeerKey(ContactId c, byte[] mlDsaKey, byte[] signature);
	}

	private static final PeerIdentity NO_IDENTITY = new PeerIdentity() {
		@Override
		public boolean knowsPeerKey(ContactId c) {
			return true;
		}

		@Override
		@Nullable
		public byte[][] ownKeyAndSignature() {
			return null;
		}

		@Override
		public void learnPeerKey(ContactId c, byte[] mlDsaKey,
				byte[] signature) {
		}
	};

	private final RootKeyStore store;
	private final RootEvolutionCrypto evolutionCrypto;
	private final CryptoComponent crypto;
	private final MlKemProvider mlKem;
	private final LongSupplier clock;
	private final PeerIdentity identity;

	private final Map<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();
	private final Map<Integer, RootEvolution> offers = new ConcurrentHashMap<>();
	private final Map<Integer, Long> lastEvolution = new ConcurrentHashMap<>();
	private final Map<Integer, Deque<Long>> answers = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> pendingDialFailures =
			new ConcurrentHashMap<>();
	private final Map<Integer, Integer> unauthenticatedDials =
			new ConcurrentHashMap<>();
	private final Map<Integer, Set<RootEvolution>> watchers =
			new ConcurrentHashMap<>();
	private final Map<Integer, Long> stalledAt = new ConcurrentHashMap<>();
	private final ScheduledThreadPoolExecutor timer = newTimer();
	@Nullable
	private volatile byte[][] identityRecords;
	private volatile boolean separatedEvolution = true;
	private volatile boolean enforcing = true;
	private volatile long deadlineMs = EVOLUTION_DEADLINE_MS;
	private volatile long liveIntervalMs = LIVE_EVOLUTION_INTERVAL_MS;
	private volatile long nudgeMs = EVOLUTION_NUDGE_MS;

	private static ScheduledThreadPoolExecutor newTimer() {
		ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1,
				r -> {
					Thread thread = new Thread(r, "root-evolution-timer");
					thread.setDaemon(true);
					return thread;
				});
		t.setRemoveOnCancelPolicy(true);
		return t;
	}

	@Inject
	public RootEvolutionManager(RootKeyStore store, CryptoComponent crypto,
			MlKemProvider mlKem, ContactIdentityExchange identity) {
		this(store, crypto, mlKem, System::currentTimeMillis, identity);
	}

	public RootEvolutionManager(RootKeyStore store, CryptoComponent crypto,
			MlKemProvider mlKem, LongSupplier clock) {
		this(store, crypto, mlKem, clock, NO_IDENTITY);
	}

	public RootEvolutionManager(RootKeyStore store, CryptoComponent crypto,
			MlKemProvider mlKem, LongSupplier clock, PeerIdentity identity) {
		this.store = store;
		this.evolutionCrypto = new RootEvolutionCrypto(crypto);
		this.crypto = crypto;
		this.mlKem = mlKem;
		this.clock = clock;
		this.identity = identity;
	}

	boolean isSeparatedEvolution() {
		return separatedEvolution;
	}

	void setSeparatedEvolution(boolean separatedEvolution) {
		this.separatedEvolution = separatedEvolution;
	}

	boolean isEnforcing() {
		return enforcing;
	}

	void setEnforcing(boolean enforcing) {
		this.enforcing = enforcing;
	}

	void setTimings(long deadlineMs, long liveIntervalMs) {
		this.deadlineMs = deadlineMs;
		this.liveIntervalMs = liveIntervalMs;
	}

	void setNudge(long nudgeMs) {
		this.nudgeMs = nudgeMs;
	}

	long nudgeMs() {
		return nudgeMs;
	}

	void stalled(ContactId c) {
		stalledAt.put(c.getInt(), now());
	}

	void healthy(ContactId c) {
		stalledAt.remove(c.getInt());
	}

	public boolean recentlyStalled(int contactId) {
		Long at = stalledAt.get(contactId);
		if (at == null) return false;
		long elapsed = now() - at;
		return elapsed >= 0 && elapsed < STALL_MEMORY_MS;
	}

	long untilLiveEvolution(ContactId c) {
		long elapsed = now() - store.evolvedAt(c);
		if (elapsed < 0 || elapsed >= liveIntervalMs) return liveIntervalMs;
		return liveIntervalMs - elapsed;
	}

	long deadlineMs() {
		return deadlineMs;
	}

	boolean isDue(ContactId c) {
		long at = store.evolvedAt(c);
		long elapsed = now() - at;
		return at == 0 || elapsed < 0 || elapsed >= MIN_EVOLUTION_INTERVAL_MS;
	}

	long liveIntervalMs() {
		return liveIntervalMs;
	}

	ScheduledFuture<?> after(long delayMs, Runnable task) {
		return timer.schedule(task, delayMs, TimeUnit.MILLISECONDS);
	}

	void watch(ContactId c, RootEvolution e) {
		watchers.computeIfAbsent(c.getInt(),
				k -> ConcurrentHashMap.newKeySet()).add(e);
	}

	boolean hasOtherConnection(ContactId c, RootEvolution e) {
		Set<RootEvolution> set = watchers.get(c.getInt());
		if (set == null) return false;
		for (RootEvolution other : set) {
			if (other != e) return true;
		}
		return false;
	}

	void unwatch(ContactId c, RootEvolution e) {
		Set<RootEvolution> set = watchers.get(c.getInt());
		if (set != null) set.remove(e);
	}

	public ZwfControlHandler newEvolution(ContactId c, boolean alice) {
		return new RootEvolution(this, c, alice);
	}

	public long dialEpoch(ContactId c, ContactRootKeys keys) {
		if (!keys.isPendingConfirmed()) return keys.getEpoch();
		int failures = pendingDialFailures.getOrDefault(c.getInt(), 0);
		if (failures > 0
				&& failures % (PENDING_DIAL_FALLBACK_AFTER + 1)
				== PENDING_DIAL_FALLBACK_AFTER) {
			return keys.getEpoch();
		}
		return keys.getPendingEpoch();
	}

	public void dialEnded(ContactId c, boolean peerAuthenticated) {
		if (peerAuthenticated) {
			pendingDialFailures.remove(c.getInt());
			Integer failed = unauthenticatedDials.remove(c.getInt());
			if (failed != null && failed >= OUT_OF_SYNC_AFTER) {
				store.markOutOfSync(c, false);
			} else if (store.isOutOfSync(c)) {
				store.markOutOfSync(c, false);
			}
			return;
		}
		pendingDialFailures.merge(c.getInt(), 1, Integer::sum);
		int failed = unauthenticatedDials.merge(c.getInt(), 1, Integer::sum);
		if (failed == OUT_OF_SYNC_AFTER) store.markOutOfSync(c, true);
	}

	RootKeyStore getStore() {
		return store;
	}

	RootEvolutionCrypto getEvolutionCrypto() {
		return evolutionCrypto;
	}

	CryptoComponent getCrypto() {
		return crypto;
	}

	MlKemProvider getMlKem() {
		return mlKem;
	}

	long now() {
		return clock.getAsLong();
	}

	ReentrantLock lock(ContactId c) {
		return locks.computeIfAbsent(c.getInt(), k -> new ReentrantLock());
	}

	boolean mayOffer(ContactId c, RootEvolution e) {
		return mayOffer(c, e, false);
	}

	boolean mayOffer(ContactId c, RootEvolution e, boolean dueNow) {
		RootEvolution other = offers.get(c.getInt());
		if (other != null && other != e) return false;
		Long last = lastEvolution.get(c.getInt());
		long now = now();
		if (!dueNow && last != null
				&& now - last < MIN_EVOLUTION_INTERVAL_MS) {
			return false;
		}
		return !store.isPaused(now) && !store.isSnapshotting();
	}

	boolean mayAnswer(ContactId c) {
		long now = now();
		if (store.isPaused(now) || store.isSnapshotting()) return false;
		Deque<Long> recent = answers.computeIfAbsent(c.getInt(),
				k -> new ArrayDeque<>());
		recent.removeIf(at -> at > now || now - at >= ANSWER_INTERVAL_MS);
		if (recent.size() >= MAX_ANSWERS_PER_INTERVAL) return false;
		recent.addLast(now);
		return true;
	}

	void offerStarted(ContactId c, RootEvolution e) {
		offers.put(c.getInt(), e);
	}

	void offerEnded(ContactId c, RootEvolution e) {
		offers.remove(c.getInt(), e);
	}

	void proved(ContactId c) {
		lastEvolution.put(c.getInt(), now());
	}

	void evolved(ContactId c) {
		long now = now();
		lastEvolution.put(c.getInt(), now);
		stalledAt.remove(c.getInt());
		store.setEvolvedAt(c, now);
		Set<RootEvolution> set = watchers.get(c.getInt());
		if (set == null) return;
		for (RootEvolution e : set) e.onEvolved();
	}

	@Nullable
	Long lastEvolution(ContactId c) {
		return lastEvolution.get(c.getInt());
	}

	boolean knowsPeerIdentity(ContactId c) {
		return identity.knowsPeerKey(c);
	}

	@Nullable
	byte[][] identityRecords() {
		byte[][] records = identityRecords;
		if (records != null) return records;
		byte[][] keyAndSignature = identity.ownKeyAndSignature();
		if (keyAndSignature == null) return null;
		records = RootEvolutionRecord.identity(keyAndSignature[0],
				keyAndSignature[1]);
		identityRecords = records;
		return records;
	}

	void learnPeerIdentity(ContactId c, byte[] mlDsaKey, byte[] signature) {
		identity.learnPeerKey(c, mlDsaKey, signature);
	}
}
