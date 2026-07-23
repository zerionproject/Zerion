package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class OutgoingSessionRecord {

	private final Collection<MessageId> ackedIds = new CopyOnWriteArrayList<>();
	private final Collection<MessageId> sentIds = new CopyOnWriteArrayList<>();

	public void onAckSent(Collection<MessageId> acked) {
		ackedIds.addAll(acked);
	}

	public void onMessageSent(MessageId sent) {
		sentIds.add(sent);
	}

	public Collection<MessageId> getAckedIds() {
		return ackedIds;
	}

	public Collection<MessageId> getSentIds() {
		return sentIds;
	}
}
