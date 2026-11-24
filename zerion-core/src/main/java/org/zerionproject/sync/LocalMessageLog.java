package org.zerionproject.sync;

import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.UserMessageMarker;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The registry of fresh messages the user chose to send. Only the clients
 * that create a message on behalf of a user action enter it here; nothing is
 * entered from database events, so a message a client adds automatically
 * while handling a record the peer sent is never fresh and never opens the
 * active cadence. An entry lives for a day or until the message first leaves
 * for a contact. The registry is kept in memory only.
 */
@ThreadSafe
@Singleton
@NotNullByDefault
public class LocalMessageLog implements UserMessageMarker {

	public static final long FRESH_MESSAGE_MS = 24 * 60 * 60_000L;
	private static final int MAX_ENTRIES = 1024;

	private final LongSupplier clock;
	private final Map<MessageId, Long> added = new LinkedHashMap<>();

	@Inject
	public LocalMessageLog() {
		this(System::currentTimeMillis);
	}

	public LocalMessageLog(LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public synchronized void noteUserMessage(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		added.remove(id);
		added.put(id, now);
		if (added.size() > MAX_ENTRIES) {
			Iterator<MessageId> it = added.keySet().iterator();
			it.next();
			it.remove();
		}
	}

	public synchronized boolean isRecentLocal(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		return added.containsKey(id);
	}

	public synchronized boolean takeFirstSend(MessageId id) {
		long now = clock.getAsLong();
		expire(now);
		return added.remove(id) != null;
	}

	private void expire(long now) {
		Iterator<Map.Entry<MessageId, Long>> it = added.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<MessageId, Long> e = it.next();
			if (now - e.getValue() > FRESH_MESSAGE_MS) it.remove();
			else break;
		}
	}
}
