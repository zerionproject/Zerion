package org.zerionproject.sync;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@ThreadSafe
@Singleton
@NotNullByDefault
public class LocalMessageLog implements EventListener {

	public static final long FRESH_MESSAGE_MS = 24 * 60 * 60_000L;
	private static final int MAX_ENTRIES = 1024;

	private final LongSupplier clock;
	private final Map<MessageId, Long> added = new LinkedHashMap<>();

	@Inject
	public LocalMessageLog(EventBus eventBus) {
		this(System::currentTimeMillis);
		eventBus.addListener(this);
	}

	public LocalMessageLog(LongSupplier clock) {
		this.clock = clock;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof MessageAddedEvent) {
			MessageAddedEvent m = (MessageAddedEvent) e;
			if (m.getContactId() == null) noteLocal(m.getMessage().getId());
		}
	}

	public synchronized void noteLocal(MessageId id) {
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
