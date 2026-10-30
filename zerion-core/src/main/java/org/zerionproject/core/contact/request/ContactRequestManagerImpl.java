package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.ContactLinkLimitException;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.FloodNotice;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestInfo;
import org.zerionproject.core.api.contact.request.OutgoingRequestLimitException;
import org.zerionproject.core.api.contact.request.OwnLinkException;
import org.zerionproject.core.api.contact.request.event.ContactLinksChangedEvent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.concurrent.TimeUnit.HOURS;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_PERMANENT;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_PER_ADD_BINDING;
import static org.zerionproject.core.api.contact.request.ContactLink.STATE_ACTIVE;

@Singleton
@ThreadSafe
@NotNullByDefault
class ContactRequestManagerImpl implements ContactRequestManager, Service,
		ContactManager.PendingContactHook {

	private final DatabaseComponent db;
	private final ContactLinkStore links;
	private final ContactRequestSender sender;
	private final ContactRequestReceiver receiver;
	private final ContactManager contactManager;
	private final IdentityManager identityManager;
	private final Clock clock;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;

	private final Object snapshotLock = new Object();
	private int snapshots = 0;
	private boolean pausedBeforeSnapshot = false;
	@Nullable
	private volatile Cancellable maintenance = null;

	@Inject
	ContactRequestManagerImpl(DatabaseComponent db, ContactLinkStore links,
			ContactRequestSender sender, ContactRequestReceiver receiver,
			ContactManager contactManager, IdentityManager identityManager,
			Clock clock, TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor) {
		this.db = db;
		this.links = links;
		this.sender = sender;
		this.receiver = receiver;
		this.contactManager = contactManager;
		this.identityManager = identityManager;
		this.clock = clock;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public void startService() {
		maintenance = scheduler.scheduleWithFixedDelay(this::maintain,
				ioExecutor, 0, 1, HOURS);
	}

	@Override
	public void stopService() {
		Cancellable task = maintenance;
		if (task != null) task.cancel();
	}

	private void maintain() {
		try {
			receiver.maintain();
		} catch (DbException e) {
		}
	}

	@Override
	public void convertingPendingContact(Transaction txn,
			org.zerionproject.core.api.contact.PendingContactId p,
			org.zerionproject.core.api.contact.ContactId c)
			throws DbException {
		sender.convertingPendingContact(txn, p, c);
		receiver.convertingPendingContact(txn, p, c);
	}

	@Override
	public InviteLink parseLink(String link)
			throws DbException, FormatException {
		InviteLink parsed = links.getCodec().parse(link);
		boolean own = db.transactionWithResult(true,
				txn -> links.isOwnLink(txn, parsed));
		if (own) throw new OwnLinkException();
		return parsed;
	}

	@Override
	public String getZeroIdLink() throws DbException {
		return db.transactionWithResult(false,
				txn -> links.linkString(links.getOrCreateZeroId(txn)));
	}

	@Override
	public void zeroIdShared() throws DbException {
		db.transaction(false, txn -> links.markShared(txn,
				links.getOrCreateZeroId(txn).getLinkId()));
	}

	@Override
	public String resetZeroId() throws DbException {
		return db.transactionWithResult(false,
				txn -> links.linkString(links.resetZeroId(txn)));
	}

	@Override
	public ContactLinkInfo createPermanentLink(String label)
			throws DbException, ContactLinkLimitException {
		Transaction txn = db.startTransaction(false);
		try {
			ContactLink l = links.createPermanent(txn, label);
			ContactLinkInfo info = info(txn, l);
			db.commitTransaction(txn);
			return info;
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void permanentLinkShared(byte[] linkId) throws DbException {
		db.transaction(false, txn -> links.markShared(txn, linkId));
	}

	@Override
	public void withdrawPermanentLink(byte[] linkId) throws DbException {
		db.transaction(false, txn -> links.withdraw(txn, linkId));
	}

	@Override
	public void resumePermanentLink(byte[] linkId) throws DbException {
		db.transaction(false, txn -> links.resume(txn, linkId));
	}

	@Override
	public List<ContactLinkInfo> getLinks() throws DbException {
		return db.transactionWithResult(true, txn -> {
			List<ContactLinkInfo> out = new ArrayList<>();
			for (ContactLink l : links.getLinks(txn)) {
				if (l.getKind() == KIND_PER_ADD_BINDING) continue;
				out.add(info(txn, l));
			}
			return out;
		});
	}

	@Override
	public List<FloodNotice> getFloodNotices() throws DbException {
		return db.transactionWithResult(true, links::getFloodNotices);
	}

	@Override
	public void dismissFloodNotice(byte[] linkId) throws DbException {
		db.transaction(false, txn -> links.dismissFloodNotice(txn, linkId));
	}

	private ContactLinkInfo info(Transaction txn, ContactLink l)
			throws DbException {
		int undecided = 0;
		for (IncomingRequest r : db.getIncomingRequests(txn)) {
			if (r.isUndecided()
					&& Arrays.equals(r.getLinkId(), l.getLinkId())) {
				undecided++;
			}
		}
		int blocked = db.getRequestBlocks(txn, l.getLinkId()).size();
		String link = l.getState() == STATE_ACTIVE ? links.linkString(l)
				: null;
		long now = clock.currentTimeMillis();
		long pausedUntil = l.getKind() == KIND_PERMANENT
				&& l.getState() == STATE_ACTIVE
				? links.getPausedUntil(txn, l.getLinkId()) : 0;
		return new ContactLinkInfo(l.getLinkId(), l.getKind(), l.getState(),
				l.getLabel(), link, l.getCreatedAt(), l.getLastSharedAt(),
				links.isInboxOpen(txn, l, now), undecided, blocked,
				links.isInboxPowProtected(l), pausedUntil > now ? pausedUntil
				: 0);
	}

	@Override
	public Collection<byte[]> getBlockedSenders(byte[] linkId)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> db.getRequestBlocks(txn, linkId));
	}

	@Override
	public void unblock(byte[] linkId, byte[] senderTag) throws DbException {
		db.transaction(false,
				txn -> db.removeRequestBlock(txn, linkId, senderTag));
	}

	@Override
	public byte[] sendRequest(String link, String alias, String shownName,
			@Nullable String note) throws DbException, FormatException,
			OutgoingRequestLimitException {
		InviteLink parsed = parseLink(link);
		if (parsed.getFlow() != InviteLink.Flow.REQUEST) {
			throw new FormatException();
		}
		return sender.submit(parsed, link.trim(), alias, shownName, note);
	}

	@Override
	public void cancelRequest(byte[] requestId) throws DbException {
		sender.cancel(requestId);
	}

	@Override
	public void dismissOutgoing(byte[] requestId) throws DbException {
		sender.dismiss(requestId);
	}

	@Override
	public List<OutgoingRequestInfo> getOutgoingRequests()
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			List<OutgoingRequestInfo> out = new ArrayList<>();
			for (OutgoingRequest r : db.getOutgoingRequests(txn)) {
				out.add(new OutgoingRequestInfo(r,
						sender.isBusy(r.getRequestId())));
			}
			return out;
		});
	}

	@Override
	public List<IncomingRequestInfo> getIncomingRequests()
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			List<IncomingRequestInfo> out = new ArrayList<>();
			Collection<ContactLink> all = links.getLinks(txn);
			for (IncomingRequest r : db.getIncomingRequests(txn)) {
				int kind = 0;
				String label = null;
				for (ContactLink l : all) {
					if (Arrays.equals(l.getLinkId(), r.getLinkId())) {
						kind = l.getKind();
						label = l.getLabel();
					}
				}
				out.add(new IncomingRequestInfo(r, kind, label));
			}
			return out;
		});
	}

	@Override
	public int getUnopenedCount() throws DbException {
		return db.transactionWithResult(true, txn -> {
			int n = 0;
			for (IncomingRequest r : db.getIncomingRequests(txn)) {
				if (r.getState() == IncomingRequest.STATE_RECEIVED) n++;
			}
			return n;
		});
	}

	@Override
	public void markOpened(byte[] requestId) throws DbException {
		receiver.markOpened(requestId);
	}

	@Override
	public PendingContact accept(byte[] requestId, String alias)
			throws DbException {
		return receiver.accept(requestId, alias);
	}

	@Override
	public void decline(byte[] requestId) throws DbException {
		receiver.decline(requestId);
	}

	@Override
	public void block(byte[] requestId) throws DbException {
		receiver.block(requestId);
	}

	@Override
	public void retryAccepted(byte[] requestId) throws DbException {
		receiver.retryAccepted(requestId);
	}

	@Override
	public void dismissIncoming(byte[] requestId) throws DbException {
		receiver.dismiss(requestId);
	}

	@Override
	public PendingContact addMutualFromInvite(String link, String alias,
			@Nullable String sharedZeroId)
			throws DbException, FormatException, GeneralSecurityException {
		return contactManager.addPendingContactFromInvite(link, alias,
				sharedZeroId);
	}

	@Override
	public boolean isPausedAfterRestore() throws DbException {
		return db.transactionWithResult(true, links::isPaused);
	}

	@Override
	public void confirmOnlyActiveDevice() throws DbException {
		db.transaction(false, txn -> links.setPaused(txn, false));
	}

	@Override
	public void beginBackupSnapshot() throws DbException {
		synchronized (snapshotLock) {
			if (snapshots > 0) {
				snapshots++;
				return;
			}
			links.setSnapshotting(true);
			try {
				db.transaction(false, txn -> {
					pausedBeforeSnapshot = links.isPauseStored(txn);
					links.setPaused(txn, true);
				});
			} catch (DbException | RuntimeException e) {
				links.setSnapshotting(false);
				throw e;
			}
			snapshots = 1;
		}
	}

	@Override
	public void endBackupSnapshot() throws DbException {
		synchronized (snapshotLock) {
			if (snapshots == 0 || --snapshots > 0) return;
			try {
				boolean restore = pausedBeforeSnapshot;
				db.transaction(false, txn -> links.setPaused(txn, restore));
			} finally {
				links.setSnapshotting(false);
				db.transaction(false,
						txn -> txn.attach(new ContactLinksChangedEvent()));
			}
		}
	}
}
