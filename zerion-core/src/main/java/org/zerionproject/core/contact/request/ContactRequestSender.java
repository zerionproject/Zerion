package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestLimitException;
import org.zerionproject.core.api.contact.request.event.ContactRequestAcceptedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestDeliveredEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestExpiredEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestsChangedEvent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.PendingContactExistsException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.contact.request.InboxClient.Outcome;
import org.zerionproject.core.contact.request.InboxClient.Puzzle;
import org.zerionproject.core.plugin.tor.InboxOnionAdapter;
import org.zerionproject.core.util.StringUtils;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.ID_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ATTEMPT_TIMEOUT_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DELIVERED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_REQUEST;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_BODY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_OPEN_OUTGOING;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PAID_SOLUTIONS_PER_HOUR;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PAID_SOLUTION_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_DIAL_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REQUEST_ID_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEND_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SLOTS_BUSY_MAX_DELAY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SLOTS_BUSY_MIN_DELAY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SLOTS_BUSY_RETRIES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TOR_UP_MAX_DELAY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TOR_UP_MIN_DELAY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_ADDED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_CANCELLED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_NOT_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_NO_ANSWER;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_QUEUED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_WITHDRAWING;

@Singleton
@ThreadSafe
@NotNullByDefault
public class ContactRequestSender implements Service, EventListener {

	enum AttemptResult {
		DELIVERED, FAILED, WAITING, SKIPPED
	}

	private final DatabaseComponent db;
	private final ContactLinkStore links;
	private final ContactRequestCrypto crypto;
	private final BdfWriterFactory writers;
	private final InboxTransportProvider transport;
	private final IdentityManager identityManager;
	private final Clock clock;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final Random random;
	private final java.util.concurrent.ExecutorService solver =
			java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
				Thread t = new Thread(task, "InviteSolver");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
	private final InboxClient client;

	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Map<String, Puzzle> solutions = new ConcurrentHashMap<>();
	private final Set<String> busyInboxes = ConcurrentHashMap.newKeySet();
	private final Map<String, ArrayDeque<Long>> paid =
			new ConcurrentHashMap<>();
	@Nullable
	private volatile Cancellable tickTask = null;
	private volatile boolean stopped = false;

	@Inject
	public ContactRequestSender(DatabaseComponent db, ContactLinkStore links,
			ContactRequestCrypto crypto, BdfWriterFactory writers,
			InboxTransportProvider transport, IdentityManager identityManager,
			Clock clock, TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor) {
		this.db = db;
		this.links = links;
		this.crypto = crypto;
		this.writers = writers;
		this.transport = transport;
		this.identityManager = identityManager;
		this.clock = clock;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		random = crypto.getCrypto().getSecureRandom();
		client = new InboxClient(crypto, random);
	}

	@Override
	public void startService() {
		tickTask = scheduler.scheduleWithFixedDelay(this::tick, ioExecutor,
				30, 30, SECONDS);
	}

	@Override
	public void stopService() {
		stopped = true;
		Cancellable task = tickTask;
		if (task != null) task.cancel();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportActiveEvent && ((TransportActiveEvent) e)
				.getTransportId().equals(TorConstants.ID)) {
			long delay = TOR_UP_MIN_DELAY_MS + (long) (random.nextDouble()
					* (TOR_UP_MAX_DELAY_MS - TOR_UP_MIN_DELAY_MS));
			scheduler.schedule(this::tick, ioExecutor, delay, MILLISECONDS);
		}
	}

	public byte[] submit(InviteLink link, String linkString, String alias,
			String shownName, @Nullable String note)
			throws DbException, OutgoingRequestLimitException {
		if (link.getFlow() != InviteLink.Flow.REQUEST) {
			throw new IllegalArgumentException();
		}
		if (!RequestPlaintext.isValidName(shownName)
				|| !RequestPlaintext.isValidNote(note)) {
			throw new IllegalArgumentException();
		}
		byte[] requestId = new byte[REQUEST_ID_BYTES];
		random.nextBytes(requestId);
		KeyPair oneTime = crypto.getCrypto().generateHybridAgreementKeyPair();
		boolean ok = db.transactionWithResult(false, txn -> {
			int open = 0;
			for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
				if (r.getState() != STATE_QUEUED
						&& r.getState() != STATE_DELIVERED) {
					continue;
				}
				open++;
				try {
					InviteLink other = links.getCodec().parse(r.getTargetLink());
					if (Arrays.equals(other.getCommitment(),
							link.getCommitment())) {
						return false;
					}
				} catch (FormatException e) {
				}
			}
			if (open >= MAX_OPEN_OUTGOING) return false;
			long now = clock.currentTimeMillis();
			db.addOutgoingRequest(txn, new OutgoingRequest(requestId,
					identityManager.getLocalAuthor(txn).getId(), linkString,
					alias, shownName, note,
					oneTime.getPublic().getEncoded(),
					oneTime.getPrivate().getEncoded().clone(), now,
					STATE_QUEUED, 0,
					now, 0, null, 0));
			txn.attach(new ContactRequestsChangedEvent());
			return true;
		});
		((HybridAgreementPrivateKey) oneTime.getPrivate()).clear();
		if (!ok) throw new OutgoingRequestLimitException();
		ioExecutor.execute(this::tick);
		return requestId;
	}

	public void cancel(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			OutgoingRequest r = find(txn, requestId);
			if (r == null) return;
			long now = clock.currentTimeMillis();
			if (r.getState() == STATE_QUEUED) {
				db.updateOutgoingRequest(txn, new OutgoingRequest(
						r.getRequestId(), r.getLocalAuthorId(),
						r.getTargetLink(), r.getAlias(), r.getShownName(),
						r.getNote(), null, null, r.getCreatedAt(),
						STATE_CANCELLED, r.getAttempts(), r.getNextAttemptAt(),
						0, null, now));
			} else if (r.getState() == STATE_DELIVERED) {
				removePending(txn, r.getPendingContactId());
				db.updateOutgoingRequest(txn, r.withdrawing(now));
			} else {
				return;
			}
			txn.attach(new ContactRequestsChangedEvent());
		});
		solutions.remove(key(requestId));
		ioExecutor.execute(this::tick);
	}

	public void dismiss(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			OutgoingRequest r = find(txn, requestId);
			if (r == null || r.isOpen()) return;
			db.removeOutgoingRequest(txn, requestId);
			txn.attach(new ContactRequestsChangedEvent());
		});
	}

	void tick() {
		if (stopped) return;
		Collection<OutgoingRequest> due;
		try {
			due = maintain();
		} catch (DbException e) {
			return;
		}
		InboxOnionAdapter adapter = transport.get();
		if (adapter == null || !adapter.isConnected()) return;
		for (OutgoingRequest r : due) {
			String k = key(r.getRequestId());
			if (!inFlight.add(k)) continue;
			ioExecutor.execute(() -> {
				try {
					attempt(r.getRequestId());
				} finally {
					inFlight.remove(k);
				}
			});
		}
	}

	private Collection<OutgoingRequest> maintain() throws DbException {
		long now = clock.currentTimeMillis();
		boolean open = db.transactionWithResult(true, txn -> {
			for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
				if (r.isOpen()) return true;
			}
			return false;
		});
		if (!open) return java.util.Collections.emptyList();
		return db.transactionWithResult(false, txn -> {
			java.util.List<OutgoingRequest> due = new java.util.ArrayList<>();
			if (links.isPaused(txn)) return due;
			for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
				int state = r.getState();
				if (state == STATE_QUEUED
						&& now >= r.getCreatedAt() + SEND_WINDOW_MS) {
					end(txn, r, STATE_NOT_DELIVERED);
				} else if (state == STATE_WITHDRAWING
						&& now >= r.getDeliveredAt() + DELIVERED_WINDOW_MS) {
					end(txn, r, STATE_CANCELLED);
				} else if (state == STATE_DELIVERED
						&& now >= r.getDeliveredAt() + DELIVERED_WINDOW_MS) {
					removePending(txn, r.getPendingContactId());
					end(txn, r, STATE_NO_ANSWER);
				} else if ((state == STATE_QUEUED
						|| state == STATE_WITHDRAWING)
						&& r.getNextAttemptAt() <= now) {
					due.add(r);
				}
			}
			return due;
		});
	}

	private void end(Transaction txn, OutgoingRequest r, int state)
			throws DbException {
		db.updateOutgoingRequest(txn, r.ended(state));
		solutions.remove(key(r.getRequestId()));
		paid.remove(key(r.getRequestId()));
		if (state == STATE_NOT_DELIVERED || state == STATE_NO_ANSWER) {
			txn.attach(new ContactRequestExpiredEvent(r.getRequestId(), true));
		} else {
			txn.attach(new ContactRequestsChangedEvent());
		}
	}

	AttemptResult attempt(byte[] requestId) {
		OutgoingRequest r;
		try {
			r = db.transactionWithResult(true, txn -> find(txn, requestId));
		} catch (DbException e) {
			return AttemptResult.SKIPPED;
		}
		if (r == null || (r.getState() != STATE_QUEUED
				&& r.getState() != STATE_WITHDRAWING)) {
			return AttemptResult.SKIPPED;
		}
		InboxOnionAdapter adapter = transport.get();
		if (adapter == null || !adapter.isConnected()) {
			return AttemptResult.SKIPPED;
		}
		InviteLink link;
		byte[] onionPub;
		try {
			link = links.getCodec().parse(r.getTargetLink());
			String inbox = link.getInbox();
			if (inbox == null) throw new FormatException();
			onionPub = links.getCodec().onionPublicKey(inbox);
		} catch (FormatException e) {
			return fail(r);
		}
		String onion = link.getInbox();
		InboxClient.Target target = new InboxClient.Target(
				link.getCommitment(), link.getX25519(), onionPub);
		byte[] clientKey = crypto.clientAuthPrivateKey(link.getBody(),
				onionPub);
		boolean withdraw = r.getState() == STATE_WITHDRAWING;
		InboxClient.Payload payload;
		try {
			payload = withdraw ? withdrawPayload(r, link)
					: requestPayload(r, link);
		} catch (DbException e) {
			return AttemptResult.SKIPPED;
		}
		String k = key(requestId);
		int busy = 0;
		try {
			for (int round = 0; round < 8; round++) {
				long now = clock.currentTimeMillis();
				Puzzle cached = solutions.get(k);
				if (cached != null && (now - cached.solvedAt >= REPLAY_WINDOW_MS
						|| now < cached.solvedAt)) {
					solutions.remove(k);
					cached = null;
				}
				Outcome o;
				Socket s = adapter.connectToInbox(onion, clientKey);
				try {
					s.setSoTimeout((int) ATTEMPT_TIMEOUT_MS);
					o = client.run(s.getInputStream(), s.getOutputStream(),
							target, cached, payload);
				} finally {
					s.close();
				}
				switch (o.result) {
					case ACKED:
						solutions.remove(k);
						busyInboxes.remove(k);
						return acked(r, link);
					case RESTART:
					case NEED_SOLUTION:
						solutions.remove(k);
						if (o.hello == null || !InboxClient.isAcceptable(o.hello)) {
							return tooHard(r);
						}
						long freeAt = paidBudgetFreeAt(k, now);
						if (freeAt > now) return wait(r, freeAt);
						if (!crypto.affordable(o.hello.difficulty)) return tooHard(r);
						byte[] x = solveInBackground(o.hello.nonce,
								link.getCommitment(), o.hello.difficulty);
						if (x == null) {
							return stopped ? AttemptResult.SKIPPED : tooHard(r);
						}
						long solvedAt = clock.currentTimeMillis();
						recordPaid(k, solvedAt);
						solutions.put(k, new Puzzle(new Solution(o.hello.epoch,
								o.hello.level, x), o.hello.nonce,
								o.hello.difficulty, solvedAt));
						continue;
					case SLOTS_BUSY:
						if (++busy <= SLOTS_BUSY_RETRIES) {
							clock.sleep(SLOTS_BUSY_MIN_DELAY_MS + (long) (
									random.nextDouble() * (SLOTS_BUSY_MAX_DELAY_MS
											- SLOTS_BUSY_MIN_DELAY_MS)));
							continue;
						}
						return fail(r);
					case TOO_HARD:
						return tooHard(r);
					case FULL:
						return fail(r);
					default:
						solutions.remove(k);
						return fail(r);
				}
			}
			return fail(r);
		} catch (IOException | InterruptedException e) {
			return fail(r);
		} finally {
			Arrays.fill(clientKey, (byte) 0);
			try {
				adapter.forgetInboxClientKey(onion);
			} catch (IOException e) {
			}
		}
	}

	private InboxClient.Payload requestPayload(OutgoingRequest r,
			InviteLink link) throws DbException {
		byte[] secret = db.transactionWithResult(false,
				links::getAccountTagSecret);
		byte[] tag = crypto.senderTag(secret, link.getCommitment());
		Arrays.fill(secret, (byte) 0);
		byte[] pub = r.getOneTimePublicKey();
		byte[] priv = r.getOneTimePrivateKey();
		if (pub == null || priv == null) throw new DbException();
		return new InboxClient.Payload() {
			@Override
			public byte[] plaintext(byte[] helloNonce)
					throws GeneralSecurityException {
				KeyPair oneTime = new KeyPair(new HybridAgreementPublicKey(pub),
						new HybridAgreementPrivateKey(priv.clone()));
				try {
					byte[] pop = crypto.proofOfPossession(link.getX25519(),
							oneTime, helloNonce, r.getRequestId());
					long hour = 3_600_000L;
					long timestamp = clock.currentTimeMillis() / hour * hour;
					return RequestPlaintext.request(r.getRequestId(),
							link.getCommitment(), oneTimeLink(pub), pop,
							r.getShownName(), r.getNote(), timestamp, tag)
							.encode(writers);
				} finally {
					((HybridAgreementPrivateKey) oneTime.getPrivate()).clear();
				}
			}

			@Override
			public byte[] requestId() {
				return r.getRequestId();
			}

			@Override
			public int kind() {
				return KIND_REQUEST;
			}
		};
	}

	private InboxClient.Payload withdrawPayload(OutgoingRequest r,
			InviteLink link) {
		return new InboxClient.Payload() {
			@Override
			public byte[] plaintext(byte[] helloNonce) {
				return RequestPlaintext.withdraw(r.getRequestId(),
						link.getCommitment()).encode(writers);
			}

			@Override
			public byte[] requestId() {
				return r.getRequestId();
			}

			@Override
			public int kind() {
				return KIND_WITHDRAW;
			}
		};
	}

	byte[] oneTimeLink(byte[] oneTimePublicKey) {
		byte[] body = new byte[LINK_BODY_BYTES];
		body[0] = (byte) LINK_TYPE_ZERO_ID;
		byte[] commitment = crypto.getCrypto().hash(HYBRID_COMMITMENT_LABEL,
				oneTimePublicKey);
		System.arraycopy(commitment, 0, body, 1, commitment.length);
		System.arraycopy(oneTimePublicKey, 0, body, 1 + commitment.length,
				X25519_BYTES);
		return body;
	}

	private AttemptResult acked(OutgoingRequest sent, InviteLink link) {
		try {
			db.transaction(false, txn -> {
				OutgoingRequest r = find(txn, sent.getRequestId());
				if (r == null) return;
				long now = clock.currentTimeMillis();
				if (r.getState() == STATE_CANCELLED
						&& sent.getState() == STATE_QUEUED) {
					db.updateOutgoingRequest(txn, new OutgoingRequest(
							r.getRequestId(), r.getLocalAuthorId(),
							r.getTargetLink(), r.getAlias(), r.getShownName(),
							r.getNote(), null, null, r.getCreatedAt(),
							STATE_WITHDRAWING, 0, now, now, null, now));
					txn.attach(new ContactRequestsChangedEvent());
					return;
				}
				if (r.getState() == STATE_WITHDRAWING) {
					if (sent.getState() == STATE_WITHDRAWING) {
						end(txn, r, STATE_CANCELLED);
					} else {
						db.updateOutgoingRequest(txn, r.scheduled(0, now));
					}
					return;
				}
				if (r.getState() != STATE_QUEUED) return;
				PendingContactId p = addPending(txn, r, link, now);
				db.updateOutgoingRequest(txn, r.delivered(now, p));
				txn.attach(new ContactRequestDeliveredEvent(r.getRequestId(),
						true));
			});
		} catch (DbException e) {
			return AttemptResult.FAILED;
		}
		paid.remove(key(sent.getRequestId()));
		return AttemptResult.DELIVERED;
	}

	@Nullable
	private PendingContactId addPending(Transaction txn, OutgoingRequest r,
			InviteLink link, long now) throws DbException {
		byte[] pub = r.getOneTimePublicKey();
		byte[] priv = r.getOneTimePrivateKey();
		if (pub == null || priv == null) return null;
		byte[] blob = link.getKeyBlob();
		PendingContactId id = new PendingContactId(
				crypto.getCrypto().hash(ID_LABEL, blob));
		PendingContact p = new PendingContact(id,
				new HybridCommitmentPublicKey(blob), r.getAlias(), now,
				FORMAT_VERSION_HYBRID, now + DELIVERED_WINDOW_MS,
				RENDEZVOUS_MODE_DIAL_ONLY);
		try {
			db.addPendingContact(txn, p, r.getLocalAuthorId(), false);
		} catch (ContactExistsException | PendingContactExistsException e) {
			return null;
		}
		db.setPendingContactOurKeys(txn, id, pub, priv);
		return id;
	}

	public boolean isBusy(byte[] requestId) {
		return busyInboxes.contains(key(requestId));
	}

	@Nullable
	private byte[] solveInBackground(byte[] nonce, byte[] linkId,
			int difficulty) throws InterruptedException {
		java.util.concurrent.Future<byte[]> f;
		try {
			f = solver.submit(() -> crypto.solve(nonce, linkId, difficulty,
					random, () -> stopped));
		} catch (java.util.concurrent.RejectedExecutionException e) {
			return null;
		}
		try {
			return f.get();
		} catch (java.util.concurrent.ExecutionException e) {
			return null;
		} catch (InterruptedException e) {
			f.cancel(true);
			throw e;
		}
	}

	private AttemptResult tooHard(OutgoingRequest r) {
		AttemptResult result = fail(r);
		busyInboxes.add(key(r.getRequestId()));
		return result;
	}

	private AttemptResult fail(OutgoingRequest r) {
		busyInboxes.remove(key(r.getRequestId()));
		try {
			db.transaction(false, txn -> {
				OutgoingRequest now = find(txn, r.getRequestId());
				if (now == null || now.getState() != r.getState()) return;
				int attempts = now.getAttempts() + 1;
				long base = now.getState() == STATE_WITHDRAWING
						? now.getCancelledAt() : now.getCreatedAt();
				long t = clock.currentTimeMillis();
				db.updateOutgoingRequest(txn, now.scheduled(attempts,
						RetrySchedule.next(base, attempts, t, random)));
			});
		} catch (DbException e) {
		}
		return AttemptResult.FAILED;
	}

	private AttemptResult wait(OutgoingRequest r, long at) {
		try {
			db.transaction(false, txn -> {
				OutgoingRequest now = find(txn, r.getRequestId());
				if (now == null || now.getState() != r.getState()) return;
				db.updateOutgoingRequest(txn,
						now.scheduled(now.getAttempts(), at));
			});
		} catch (DbException e) {
		}
		return AttemptResult.WAITING;
	}

	private long paidBudgetFreeAt(String k, long now) {
		ArrayDeque<Long> times = paid.computeIfAbsent(k,
				x -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty()
					&& times.peekFirst() <= now - PAID_SOLUTION_WINDOW_MS) {
				times.removeFirst();
			}
			if (times.size() < PAID_SOLUTIONS_PER_HOUR) return now;
			return times.peekFirst() + PAID_SOLUTION_WINDOW_MS;
		}
	}

	private void recordPaid(String k, long at) {
		ArrayDeque<Long> times = paid.computeIfAbsent(k,
				x -> new ArrayDeque<>());
		synchronized (times) {
			times.addLast(at);
		}
	}

	void convertingPendingContact(Transaction txn, PendingContactId p,
			ContactId c) throws DbException {
		for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
			if (r.getState() == STATE_DELIVERED
					&& p.equals(r.getPendingContactId())) {
				db.updateOutgoingRequest(txn, r.ended(STATE_ADDED));
				txn.attach(new ContactRequestAcceptedEvent(r.getRequestId(),
						true));
				txn.attach(new ContactRequestAddedEvent(r.getRequestId(),
						true, c));
			}
		}
	}

	private void removePending(Transaction txn, @Nullable PendingContactId p)
			throws DbException {
		if (p == null) return;
		for (PendingContact pc : db.getPendingContacts(txn)) {
			if (pc.getId().equals(p)) {
				db.removePendingContact(txn, p);
				return;
			}
		}
	}

	@Nullable
	private OutgoingRequest find(Transaction txn, byte[] requestId)
			throws DbException {
		for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
			if (Arrays.equals(r.getRequestId(), requestId)) return r;
		}
		return null;
	}

	private static String key(byte[] requestId) {
		return StringUtils.toHexString(requestId);
	}
}
