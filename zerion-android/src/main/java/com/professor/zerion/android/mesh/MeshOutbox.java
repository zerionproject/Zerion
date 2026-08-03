package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class MeshOutbox {

	static final long TTL_MS = 7L * 24 * 3600 * 1000;
	static final int MAX_ATTEMPTS = 24;
	static final int MAX_ENTRIES = 512;

	static final class Entry {
		final ContactId contactId;
		final MessageId messageId;
		final String text;
		final long firstSeenMs;
		int attempts;

		Entry(ContactId contactId, MessageId messageId, String text,
				long firstSeenMs) {
			this.contactId = contactId;
			this.messageId = messageId;
			this.text = text;
			this.firstSeenMs = firstSeenMs;
		}
	}

	private final Map<String, Entry> pending =
			new LinkedHashMap<String, Entry>() {
				@Override
				protected boolean removeEldestEntry(
						Map.Entry<String, MeshOutbox.Entry> eldest) {
					return size() > MAX_ENTRIES;
				}
			};

	@Inject
	MeshOutbox() {
	}

	void add(ContactId contactId, MessageId messageId, String text,
			long composeTimeMs) {
		synchronized (pending) {
			if (!pending.containsKey(key(messageId))) {
				pending.put(key(messageId),
						new Entry(contactId, messageId, text, composeTimeMs));
			}
		}
	}

	boolean remove(ContactId contactId, MessageId messageId) {
		synchronized (pending) {
			Entry e = pending.get(key(messageId));
			if (e == null || !e.contactId.equals(contactId)) return false;
			return pending.remove(key(messageId)) != null;
		}
	}

	List<Entry> snapshot() {
		synchronized (pending) {
			return new ArrayList<>(pending.values());
		}
	}

	void drop(MessageId messageId) {
		synchronized (pending) {
			pending.remove(key(messageId));
		}
	}

	void clear() {
		synchronized (pending) {
			pending.clear();
		}
	}

	private static String key(MessageId messageId) {
		return messageId.toString();
	}
}
