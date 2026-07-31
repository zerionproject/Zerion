package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

	private final Map<String, Entry> pending = new ConcurrentHashMap<>();

	@Inject
	MeshOutbox() {
	}

	void add(ContactId contactId, MessageId messageId, String text,
			long composeTimeMs) {
		evictIfFull();
		pending.putIfAbsent(key(messageId),
				new Entry(contactId, messageId, text, composeTimeMs));
	}

	private void evictIfFull() {
		while (pending.size() >= MAX_ENTRIES) {
			String oldestKey = null;
			long oldest = Long.MAX_VALUE;
			for (Map.Entry<String, Entry> e : pending.entrySet()) {
				if (e.getValue().firstSeenMs < oldest) {
					oldest = e.getValue().firstSeenMs;
					oldestKey = e.getKey();
				}
			}
			if (oldestKey == null || pending.remove(oldestKey) == null) return;
		}
	}

	boolean remove(ContactId contactId, MessageId messageId) {
		Entry e = pending.get(key(messageId));
		if (e == null || !e.contactId.equals(contactId)) return false;
		return pending.remove(key(messageId), e);
	}

	List<Entry> snapshot() {
		return new ArrayList<>(pending.values());
	}

	void drop(MessageId messageId) {
		pending.remove(key(messageId));
	}

	void clear() {
		pending.clear();
	}

	private static String key(MessageId messageId) {
		return messageId.toString();
	}
}
