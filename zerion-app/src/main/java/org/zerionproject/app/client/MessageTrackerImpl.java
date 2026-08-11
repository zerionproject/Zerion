package org.zerionproject.app.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.app.api.client.MessageTracker;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_LATEST_MSG;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_MSG_COUNT;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_STORED_MESSAGE_ID;
import static org.zerionproject.app.client.MessageTrackerConstants.GROUP_KEY_UNREAD_COUNT;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;

@Immutable
@NotNullByDefault
class MessageTrackerImpl implements MessageTracker {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final Clock clock;

	@Inject
	MessageTrackerImpl(DatabaseComponent db, ClientHelper clientHelper,
			Clock clock) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.clock = clock;
	}

	@Override
	public void initializeGroupCount(Transaction txn, GroupId g)
			throws DbException {
		long now = clock.currentTimeMillis();
		GroupCount groupCount = new GroupCount(0, 0, now);
		storeGroupCount(txn, g, groupCount);
	}

	@Override
	public void trackIncomingMessage(Transaction txn, Message m)
			throws DbException {
		trackMessage(txn, m.getGroupId(), m.getTimestamp(), false);
	}

	@Override
	public void trackOutgoingMessage(Transaction txn, Message m)
			throws DbException {
		trackMessage(txn, m.getGroupId(), m.getTimestamp(), true);
	}

	@Override
	public void trackMessage(Transaction txn, GroupId g, long time,
			boolean read) throws DbException {
		GroupCount c = getGroupCount(txn, g);
		int msgCount = c.getMsgCount() + 1;
		int unreadCount = c.getUnreadCount() + (read ? 0 : 1);
		long latestMsgTime = Math.max(c.getLatestMsgTime(), time);
		storeGroupCount(txn, g, new GroupCount(msgCount, unreadCount,
				latestMsgTime));
	}

	@Nullable
	@Override
	public MessageId loadStoredMessageId(GroupId g) throws DbException {
		try {
			BdfDictionary d = clientHelper.getGroupMetadataAsDictionary(g);
			byte[] msgBytes = d.getOptionalRaw(GROUP_KEY_STORED_MESSAGE_ID);
			return msgBytes != null ? new MessageId(msgBytes) : null;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void storeMessageId(GroupId g, MessageId m) throws DbException {
		BdfDictionary d = BdfDictionary.of(
				new BdfEntry(GROUP_KEY_STORED_MESSAGE_ID, m)
		);
		try {
			clientHelper.mergeGroupMetadata(g, d);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public GroupCount getGroupCount(GroupId g) throws DbException {
		GroupCount count;
		Transaction txn = db.startTransaction(true);
		try {
			count = getGroupCount(txn, g);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		return count;
	}

	@Override
	public GroupCount getGroupCount(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary d = clientHelper.getGroupMetadataAsDictionary(txn, g);
			return new GroupCount(
					d.getInt(GROUP_KEY_MSG_COUNT, 0),
					d.getInt(GROUP_KEY_UNREAD_COUNT, 0),
					d.getLong(GROUP_KEY_LATEST_MSG, 0L)
			);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void storeGroupCount(Transaction txn, GroupId g, GroupCount c)
			throws DbException {
		try {
			BdfDictionary d = BdfDictionary.of(
					new BdfEntry(GROUP_KEY_MSG_COUNT, c.getMsgCount()),
					new BdfEntry(GROUP_KEY_UNREAD_COUNT, c.getUnreadCount()),
					new BdfEntry(GROUP_KEY_LATEST_MSG, c.getLatestMsgTime())
			);
			clientHelper.mergeGroupMetadata(txn, g, d);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public boolean setReadFlag(Transaction txn, GroupId g, MessageId m,
			boolean read) throws DbException {
		try {
			BdfDictionary old =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			boolean wasRead = old.getBoolean(MSG_KEY_READ, false);
			if (wasRead != read) {
				BdfDictionary meta = new BdfDictionary();
				meta.put(MSG_KEY_READ, read);
				clientHelper.mergeMessageMetadata(txn, m, meta);
				GroupCount c = getGroupCount(txn, g);
				int unreadCount = c.getUnreadCount() + (read ? -1 : 1);
				if (unreadCount < 0) unreadCount = 0;
				storeGroupCount(txn, g, new GroupCount(c.getMsgCount(),
						unreadCount, c.getLatestMsgTime()));
			}
			return wasRead;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void resetGroupCount(Transaction txn, GroupId g, int msgCount,
			int unreadCount) throws DbException {
		long now = clock.currentTimeMillis();
		GroupCount groupCount = new GroupCount(msgCount, unreadCount, now);
		storeGroupCount(txn, g, groupCount);
	}

}
