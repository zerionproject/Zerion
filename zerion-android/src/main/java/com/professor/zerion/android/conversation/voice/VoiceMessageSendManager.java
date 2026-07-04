package com.professor.zerion.android.conversation.voice;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.MessagesSentEvent;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class VoiceMessageSendManager implements EventListener {

	private final Executor dbExecutor;
	private final TransactionManager db;
	private final MessagingManager messagingManager;
	private final PrivateMessageFactory privateMessageFactory;
	private final ConversationManager conversationManager;
	private final VoiceChunkAssembler assembler;

	private static final int MAX_PENDING_MEMOS = 64;

	private final Object lock = new Object();
	private final Map<String, PendingMemo> memos = new LinkedHashMap<>();
	private final Map<MessageId, String> inFlight = new HashMap<>();

	private static class PendingMemo {
		private final ContactId contactId;
		private final GroupId groupId;
		private final List<String> laterParts;
		private int nextIndex;

		private PendingMemo(ContactId contactId, GroupId groupId,
				List<String> laterParts) {
			this.contactId = contactId;
			this.groupId = groupId;
			this.laterParts = laterParts;
		}
	}

	@Inject
	VoiceMessageSendManager(EventBus eventBus,
			@DatabaseExecutor Executor dbExecutor,
			TransactionManager db,
			MessagingManager messagingManager,
			PrivateMessageFactory privateMessageFactory,
			ConversationManager conversationManager,
			VoiceChunkAssembler assembler) {
		this.dbExecutor = dbExecutor;
		this.db = db;
		this.messagingManager = messagingManager;
		this.privateMessageFactory = privateMessageFactory;
		this.conversationManager = conversationManager;
		this.assembler = assembler;
		eventBus.addListener(this);
	}

	public String newMemoId() {
		return VoiceMessageChunkFormat.newMemoId();
	}

	public List<String> split(String fullVoiceText, String memoId) {
		assembler.putComplete(memoId, fullVoiceText);
		return VoiceMessageChunkFormat.split(fullVoiceText, memoId);
	}

	public void register(ContactId contactId, GroupId groupId, String memoId,
			List<String> laterParts, MessageId afterPartId) {
		if (laterParts.isEmpty()) return;
		synchronized (lock) {
			while (memos.size() >= MAX_PENDING_MEMOS) {
				java.util.Iterator<String> it = memos.keySet().iterator();
				if (!it.hasNext()) break;
				String oldest = it.next();
				it.remove();
				inFlight.values().remove(oldest);
			}
			memos.put(memoId, new PendingMemo(contactId, groupId, laterParts));
			inFlight.put(afterPartId, memoId);
		}
	}

	public void cancelMemo(String memoId) {
		synchronized (lock) {
			memos.remove(memoId);
			inFlight.values().remove(memoId);
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessagesSentEvent) {
			MessagesSentEvent m = (MessagesSentEvent) e;
			for (MessageId id : m.getMessageIds()) {
				String memoId;
				synchronized (lock) {
					memoId = inFlight.remove(id);
				}
				if (memoId != null) sendNextPart(memoId);
			}
		} else if (e instanceof ContactRemovedEvent) {
			ContactId removed = ((ContactRemovedEvent) e).getContactId();
			synchronized (lock) {
				java.util.Set<String> dropped = new java.util.HashSet<>();
				java.util.Iterator<Map.Entry<String, PendingMemo>> it =
						memos.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<String, PendingMemo> entry = it.next();
					if (entry.getValue().contactId.equals(removed)) {
						dropped.add(entry.getKey());
						it.remove();
					}
				}
				inFlight.values().removeAll(dropped);
			}
		}
	}

	private void sendNextPart(String memoId) {
		dbExecutor.execute(() -> {
			final String partText;
			final ContactId contactId;
			final GroupId groupId;
			final boolean last;
			synchronized (lock) {
				PendingMemo memo = memos.get(memoId);
				if (memo == null) return;
				if (memo.nextIndex >= memo.laterParts.size()) {
					memos.remove(memoId);
					return;
				}
				partText = memo.laterParts.get(memo.nextIndex);
				contactId = memo.contactId;
				groupId = memo.groupId;
				memo.nextIndex++;
				last = memo.nextIndex >= memo.laterParts.size();
			}
			try {
				MessageId id = db.transactionWithResult(false, txn -> {
					long timestamp = conversationManager
						.getTimestampForOutgoingMessage(txn, contactId);
					PrivateMessage pm;
					try {
						pm = privateMessageFactory.createLegacyPrivateMessage(
							groupId, timestamp, partText);
					} catch (FormatException fe) {
						throw new DbException(fe);
					}
					messagingManager.addLocalMessage(txn, pm);
					return pm.getMessage().getId();
				});
				if (last) {
					synchronized (lock) {
						memos.remove(memoId);
					}
				} else {
					synchronized (lock) {
						inFlight.put(id, memoId);
					}
				}
			} catch (DbException ex) {
				synchronized (lock) {
					memos.remove(memoId);
				}
			}
		});
	}
}
