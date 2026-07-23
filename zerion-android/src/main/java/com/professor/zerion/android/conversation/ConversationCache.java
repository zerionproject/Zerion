package com.professor.zerion.android.conversation;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class ConversationCache {

	private static final int MAX_CACHED_MESSAGES = 50;
	private static final int MAX_CACHED_CONVERSATIONS = 10;
	private static final long CACHE_EXPIRY_MS = 300_000;

	private static volatile ConversationCache instance;

	private final Map<ContactId, CachedConversation> cache = new ConcurrentHashMap<>();
	private final List<ContactId> accessOrder = Collections.synchronizedList(new ArrayList<>());

	private ConversationCache() {}

	public static ConversationCache getInstance() {
		if (instance == null) {
			synchronized (ConversationCache.class) {
				if (instance == null) {
					instance = new ConversationCache();
				}
			}
		}
		return instance;
	}

	public void put(ContactId contactId, List<ConversationMessageHeader> headers) {
		put(contactId, headers, null);
	}

	public void put(ContactId contactId, List<ConversationMessageHeader> headers,
			@Nullable Map<MessageId, String> texts) {
		synchronized (accessOrder) {
			if (cache.size() >= MAX_CACHED_CONVERSATIONS && !cache.containsKey(contactId)) {
				if (!accessOrder.isEmpty()) {
					ContactId oldest = accessOrder.remove(0);
					cache.remove(oldest);
				}
			}
			accessOrder.remove(contactId);
			accessOrder.add(contactId);
		}
		List<ConversationMessageHeader> limitedHeaders;
		if (headers.size() > MAX_CACHED_MESSAGES) {
			limitedHeaders = new ArrayList<>(headers.subList(0, MAX_CACHED_MESSAGES));
		} else {
			limitedHeaders = new ArrayList<>(headers);
		}

		cache.put(contactId, new CachedConversation(limitedHeaders, texts, System.currentTimeMillis()));
	}

	public Map<MessageId, String> getCachedTexts(ContactId contactId) {
		CachedConversation cached = cache.get(contactId);
		if (cached == null) {
			return new HashMap<>();
		}
		return new HashMap<>(cached.texts);
	}

	@Nullable
	public List<ConversationMessageHeader> getSnapshot(ContactId contactId) {
		CachedConversation cached = cache.get(contactId);
		if (cached == null) {
			return null;
		}
		if (System.currentTimeMillis() - cached.timestamp > CACHE_EXPIRY_MS) {
			cache.remove(contactId);
			synchronized (accessOrder) {
				accessOrder.remove(contactId);
			}
			return null;
		}
		synchronized (accessOrder) {
			accessOrder.remove(contactId);
			accessOrder.add(contactId);
		}

		return new ArrayList<>(cached.headers);
	}

	public boolean hasValidCache(ContactId contactId) {
		CachedConversation cached = cache.get(contactId);
		if (cached == null) return false;
		return System.currentTimeMillis() - cached.timestamp <= CACHE_EXPIRY_MS;
	}

	public void addMessage(ContactId contactId, ConversationMessageHeader header) {
		CachedConversation cached = cache.get(contactId);
		if (cached != null) {
			synchronized (cached) {
				cached.headers.add(0, header);
				while (cached.headers.size() > MAX_CACHED_MESSAGES) {
					cached.headers.remove(cached.headers.size() - 1);
				}
				cached.timestamp = System.currentTimeMillis();
			}
		}
	}

	public void removeMessage(ContactId contactId, MessageId messageId) {
		CachedConversation cached = cache.get(contactId);
		if (cached != null) {
			synchronized (cached) {
				cached.headers.removeIf(h -> h.getId().equals(messageId));
				cached.texts.remove(messageId);
			}
		}
	}

	public void invalidate(ContactId contactId) {
		cache.remove(contactId);
		synchronized (accessOrder) {
			accessOrder.remove(contactId);
		}
	}

	public void clearAll() {
		cache.clear();
		synchronized (accessOrder) {
			accessOrder.clear();
		}
	}

	private static class CachedConversation {
		final List<ConversationMessageHeader> headers;
		final Map<MessageId, String> texts;
		long timestamp;

		CachedConversation(List<ConversationMessageHeader> headers,
				@Nullable Map<MessageId, String> texts, long timestamp) {
			this.headers = Collections.synchronizedList(new ArrayList<>(headers));
			this.texts = texts != null ? new ConcurrentHashMap<>(texts) : new ConcurrentHashMap<>();
			this.timestamp = timestamp;
		}
	}
}
