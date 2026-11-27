package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.PoliteExecutor;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.FloodNotice;
import org.zerionproject.core.api.contact.request.event.ContactLinksChangedEvent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.plugin.tor.InboxOnionAdapter;
import org.zerionproject.core.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_RETRY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.INBOX_SECRET_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNPUBLISH_GRACE_MS;
import static org.zerionproject.core.util.IoUtils.tryToClose;

@Singleton
@ThreadSafe
@NotNullByDefault
public class InboxService implements Service, EventListener {

	private final DatabaseComponent db;
	private final ContactLinkStore links;
	private final ContactRequestReceiver receiver;
	private final ContactRequestCrypto crypto;
	private final BdfReaderFactory readers;
	private final InboxTransportProvider transport;
	private final Clock clock;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final Executor worker;

	private final Map<String, InboxPuzzle> puzzles = new ConcurrentHashMap<>();
	private final Map<String, InboxSlots> slots = new ConcurrentHashMap<>();
	private final Map<String, Published> published = new HashMap<>();
	private final Set<Published> inGrace = ConcurrentHashMap.newKeySet();
	@Nullable
	private volatile Cancellable refreshTask = null;
	private final Object resumeLock = new Object();
	@Nullable
	private Cancellable resumeTask = null;
	private long resumeAt = 0;

	private final class Published implements InboxSession.Admission {

		final byte[] linkId;
		final String onion;
		final OnionTargetListener listener;
		final InboxSession.Inbox inbox;
		final Set<InboxSession.Connection> live =
				ConcurrentHashMap.newKeySet();
		private final Set<InboxSession.Connection> admitted = new HashSet<>();
		volatile boolean closed = false;
		private boolean keyCleared = false;
		@Nullable
		private Cancellable graceTask = null;

		Published(byte[] linkId, String onion, OnionTargetListener listener,
				KeyPair linkKeys, InboxPuzzle puzzle, InboxSlots inboxSlots,
				byte[] onionPub) {
			this.linkId = linkId;
			this.onion = onion;
			this.listener = listener;
			inbox = new InboxSession.Inbox(linkId, onionPub, linkKeys, puzzle,
					inboxSlots, this);
		}

		@Override
		public boolean enter(InboxSession.Connection c) {
			synchronized (this) {
				if (closed) return false;
				admitted.add(c);
				links.hold(linkId);
			}
			return true;
		}

		@Override
		public void leave(InboxSession.Connection c) {
			boolean clear;
			Cancellable task;
			synchronized (this) {
				if (!admitted.remove(c)) return;
				clear = closed && admitted.isEmpty() && !keyCleared;
				if (clear) keyCleared = true;
				task = clear ? graceTask : null;
				if (clear) graceTask = null;
			}
			if (task != null) task.cancel();
			if (clear) {
				inGrace.remove(this);
				clearKey(inbox);
			}
			if (links.release(linkId)) removeUnneededLinks();
		}

		List<InboxSession.Connection> close() {
			synchronized (this) {
				closed = true;
				return new ArrayList<>(admitted);
			}
		}

		boolean isAdmitted(InboxSession.Connection c) {
			synchronized (this) {
				return admitted.contains(c);
			}
		}

		void clearNowOrAfterGrace(boolean keepAdmitted) {
			List<InboxSession.Connection> open;
			synchronized (this) {
				if (keyCleared) return;
				open = new ArrayList<>(admitted);
				if (open.isEmpty() || !keepAdmitted) {
					keyCleared = true;
					open.clear();
				}
			}
			if (open.isEmpty()) {
				if (!keepAdmitted) {
					for (InboxSession.Connection c : close()) c.close();
				}
				clearKey(inbox);
				return;
			}
			Cancellable task;
			try {
				task = scheduler.schedule(this::graceEnded, ioExecutor,
						UNPUBLISH_GRACE_MS, MILLISECONDS);
			} catch (RejectedExecutionException shuttingDown) {
				graceEnded();
				return;
			}
			synchronized (this) {
				if (!keyCleared) {
					graceTask = task;
					inGrace.add(this);
					return;
				}
			}
			task.cancel();
		}

		void graceEnded() {
			List<InboxSession.Connection> open;
			Cancellable task;
			synchronized (this) {
				task = graceTask;
				graceTask = null;
				inGrace.remove(this);
				if (keyCleared) return;
				keyCleared = true;
				open = new ArrayList<>(admitted);
			}
			if (task != null) task.cancel();
			for (InboxSession.Connection c : open) c.close();
			clearKey(inbox);
		}
	}

	private static void clearKey(InboxSession.Inbox inbox) {
		PrivateKey priv = inbox.linkKeys.getPrivate();
		if (priv instanceof HybridAgreementPrivateKey) {
			((HybridAgreementPrivateKey) priv).clear();
		}
	}

	private void removeUnneededLinks() {
		try {
			db.transaction(false, links::removeUnneededLinks);
		} catch (DbException e) {
		}
	}

	@Inject
	public InboxService(DatabaseComponent db, ContactLinkStore links,
			ContactRequestReceiver receiver, ContactRequestCrypto crypto,
			BdfReaderFactory readers, InboxTransportProvider transport,
			Clock clock, TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor) {
		this.db = db;
		this.links = links;
		this.receiver = receiver;
		this.crypto = crypto;
		this.readers = readers;
		this.transport = transport;
		this.clock = clock;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		worker = new PoliteExecutor("InboxService", ioExecutor, 1);
	}

	@Override
	public void startService() {
		refreshTask = scheduler.scheduleWithFixedDelay(this::refreshAsync,
				ioExecutor, 1, 1, MINUTES);
		worker.execute(this::removeUnneededLinks);
		refreshAsync();
	}

	@Override
	public void stopService() {
		Cancellable task = refreshTask;
		if (task != null) task.cancel();
		worker.execute(() -> {
			unpublishAll(transport.get(), false);
			for (Published p : new ArrayList<>(inGrace)) p.graceEnded();
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactLinksChangedEvent) {
			refreshAsync();
		} else if (e instanceof TransportActiveEvent) {
			if (((TransportActiveEvent) e).getTransportId()
					.equals(TorConstants.ID)) {
				refreshAsync();
			}
		} else if (e instanceof TransportInactiveEvent) {
			if (((TransportInactiveEvent) e).getTransportId()
					.equals(TorConstants.ID)) {
				refreshAsync();
			}
		}
	}

	void refreshAsync() {
		worker.execute(this::refresh);
	}

	Collection<String> getPublishedOnions() {
		List<String> onions = new ArrayList<>();
		synchronized (published) {
			for (Published p : published.values()) onions.add(p.onion);
		}
		return onions;
	}

	@Nullable
	InboxPuzzle getPuzzle(byte[] linkId) {
		return puzzles.get(StringUtils.toHexString(linkId));
	}

	@Nullable
	InboxSlots getSlots(byte[] linkId) {
		return slots.get(StringUtils.toHexString(linkId));
	}

	void refresh() {
		InboxOnionAdapter adapter = transport.get();
		Map<String, ContactLink> wanted = new HashMap<>();
		long[] nextPauseEnd = {0};
		if (adapter != null && adapter.isConnected()) {
			try {
				db.transaction(true, txn -> {
					if (links.isPaused(txn)) return;
					long now = clock.currentTimeMillis();
					for (ContactLink l : links.getInboxLinks(txn)) {
						if (links.isInboxOpen(txn, l, now)) {
							wanted.put(StringUtils.toHexString(l.getLinkId()),
									l);
						}
					}
					nextPauseEnd[0] = links.nextPauseEnd(txn, now);
				});
			} catch (DbException e) {
				return;
			}
		}
		scheduleRefreshAt(nextPauseEnd[0]);
		List<String> stale = new ArrayList<>();
		synchronized (published) {
			for (String key : published.keySet()) {
				if (!wanted.containsKey(key)) stale.add(key);
			}
		}
		for (String key : stale) unpublish(adapter, key);
		if (adapter == null) return;
		for (Map.Entry<String, ContactLink> e : wanted.entrySet()) {
			boolean present;
			synchronized (published) {
				present = published.containsKey(e.getKey());
			}
			if (!present) publish(adapter, e.getKey(), e.getValue());
		}
	}

	private void scheduleRefreshAt(long at) {
		synchronized (resumeLock) {
			if (resumeTask != null) {
				if (at == resumeAt) return;
				resumeTask.cancel();
				resumeTask = null;
				resumeAt = 0;
			}
			if (at <= 0) return;
			long delay = Math.max(1, at - clock.currentTimeMillis());
			try {
				resumeTask = scheduler.schedule(() -> {
					synchronized (resumeLock) {
						resumeTask = null;
						resumeAt = 0;
					}
					refreshAsync();
				}, ioExecutor, delay, MILLISECONDS);
				resumeAt = at;
			} catch (RejectedExecutionException shuttingDown) {
			}
		}
	}

	@Nullable
	FloodNotice flood(byte[] linkId) {
		FloodNotice acted;
		try {
			acted = db.transactionWithNullableResult(false,
					txn -> links.flooded(txn, linkId));
		} catch (DbException e) {
			try {
				scheduler.schedule(() -> worker.execute(() -> flood(linkId)),
						ioExecutor, FLOOD_RETRY_MS, MILLISECONDS);
			} catch (RejectedExecutionException shuttingDown) {
			}
			return null;
		}
		String key = StringUtils.toHexString(linkId);
		puzzles.remove(key);
		slots.remove(key);
		if (acted == null) return null;
		unpublish(transport.get(), key);
		refresh();
		return acted;
	}

	private void unpublishAll(@Nullable InboxOnionAdapter adapter,
			boolean keepAdmitted) {
		List<String> all;
		synchronized (published) {
			all = new ArrayList<>(published.keySet());
		}
		for (String key : all) unpublish(adapter, key, keepAdmitted);
	}

	private void unpublish(@Nullable InboxOnionAdapter adapter, String key) {
		unpublish(adapter, key, true);
	}

	private void unpublish(@Nullable InboxOnionAdapter adapter, String key,
			boolean keepAdmitted) {
		Published p;
		synchronized (published) {
			p = published.remove(key);
		}
		if (p == null) return;
		p.close();
		links.setInboxPowProtected(p.onion, false);
		if (adapter != null) {
			try {
				adapter.removeInboxOnion(p.onion);
			} catch (IOException e) {
			}
		}
		tryToClose(p.listener);
		for (InboxSession.Connection c : p.live) {
			if (!keepAdmitted || !p.isAdmitted(c)) c.close();
		}
		p.live.clear();
		p.clearNowOrAfterGrace(keepAdmitted);
	}

	@Nullable
	InboxSession.Inbox getInbox(byte[] linkId) {
		synchronized (published) {
			Published p = published.get(StringUtils.toHexString(linkId));
			return p == null ? null : p.inbox;
		}
	}

	private void publish(InboxOnionAdapter adapter, String key,
			ContactLink link) {
		byte[] linkId = link.getLinkId();
		InboxPuzzle puzzle = puzzles.computeIfAbsent(key, k -> {
			byte[] secret = new byte[INBOX_SECRET_BYTES];
			crypto.getCrypto().getSecureRandom().nextBytes(secret);
			return new InboxPuzzle(crypto, secret, linkId,
					() -> worker.execute(() -> flood(linkId)));
		});
		InboxSlots inboxSlots = slots.computeIfAbsent(key,
				k -> new InboxSlots());
		OnionTargetListener listener = null;
		String publishedOnion = null;
		Published entry = null;
		try {
			String onion = links.inboxOnion(link);
			byte[] onionPub = links.getCodec().onionPublicKey(onion);
			byte[] clientPub = crypto.clientAuthPublicKey(links.body(link),
					onionPub);
			listener = adapter.openOnionTarget();
			String published = adapter.publishInboxOnion(
					listener.getTorTarget(), links.inboxKeyBlob(link),
					clientPub);
			if (!published.equals(onion)) {
				adapter.removeInboxOnion(published);
				tryToClose(listener);
				return;
			}
			publishedOnion = published;
			links.setInboxPowProtected(onion,
					adapter.isInboxPowProtected(onion));
			entry = new Published(link.getLinkId(), onion, listener,
					links.keyPair(link), puzzle, inboxSlots, onionPub);
			synchronized (this.published) {
				this.published.put(key, entry);
			}
			Published accepting = entry;
			ioExecutor.execute(() -> acceptLoop(accepting));
		} catch (IOException | RuntimeException e) {
			if (entry != null) {
				synchronized (this.published) {
					if (this.published.get(key) == entry) this.published.remove(key);
				}
				entry.close();
				entry.clearNowOrAfterGrace(false);
			}
			if (publishedOnion != null) {
				links.setInboxPowProtected(publishedOnion, false);
				try {
					adapter.removeInboxOnion(publishedOnion);
				} catch (IOException | RuntimeException removeFailed) {
				}
			}
			if (listener != null) tryToClose(listener);
		}
	}

	private void acceptLoop(Published entry) {
		while (true) {
			Socket s;
			try {
				s = entry.listener.accept();
			} catch (IOException e) {
				return;
			}
			InboxSession.Connection c = connection(s);
			entry.live.add(c);
			if (entry.closed) {
				entry.live.remove(c);
				c.close();
				continue;
			}
			ioExecutor.execute(() -> {
				try {
					session(entry.inbox).run(c);
				} finally {
					entry.live.remove(c);
				}
			});
		}
	}

	InboxSession session(InboxSession.Inbox inbox) {
		return new InboxSession(crypto, readers, clock,
				(task, delay) -> scheduler.schedule(task, ioExecutor, delay,
						MILLISECONDS), inbox,
				receiver.forLink(inbox.linkId));
	}

	static InboxSession.Connection connection(Socket s) {
		return new InboxSession.Connection() {
			@Override
			public InputStream getInputStream() throws IOException {
				return s.getInputStream();
			}

			@Override
			public OutputStream getOutputStream() throws IOException {
				return s.getOutputStream();
			}

			@Override
			public void close() {
				tryToClose(s);
			}
		};
	}
}
