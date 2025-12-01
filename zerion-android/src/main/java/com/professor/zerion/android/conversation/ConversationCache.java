package com.professor.zerion.android.conversation;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Global cache for conversation messages to enable instant chat loading.
 * Pre-loads messages before opening ConversationActivity for Signal/SimpleX-level UX.
 *
 * Usage:
 * 1. Call preLoad() when user is about to open a conversation (e.g., on item click)
 * 2. In ConversationActivity.onCreate(), call getSnapshot() to get cached messages instantly
 * 3. Background load will update the cache with fresh data
 */
@ThreadSafe
@NotNullByDefault
public class ConversationCache {

	private static final int MAX_CACHED_MESSAGES = 50;
	private static final int MAX_CACHED_CONVERSATIONS = 10;
	private static final long CACHE_EXPIRY_MS = 60_000; // 1 minute

	// Thread-safe singleton
	private static volatile ConversationCache instance;

	// Cache storage
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

	/**
	 * Store pre-loaded messages for a conversation.
	 * Called from background thread before opening ConversationActivity.
	 */
	public void put(ContactId contactId, List<ConversationMessageHeader> headers) {
		// Evict oldest if at capacity
		synchronized (accessOrder) {
			if (cache.size() >= MAX_CACHED_CONVERSATIONS && !cache.containsKey(contactId)) {
				if (!accessOrder.isEmpty()) {
					ContactId oldest = accessOrder.remove(0);
					cache.remove(oldest);
				}
			}
			// Update access order
			accessOrder.remove(contactId);
			accessOrder.add(contactId);
		}

		// Limit to last N messages (most recent)
		List<ConversationMessageHeader> limitedHeaders;
		if (headers.size() > MAX_CACHED_MESSAGES) {
			limitedHeaders = new ArrayList<>(headers.subList(0, MAX_CACHED_MESSAGES));
		} else {
			limitedHeaders = new ArrayList<>(headers);
		}

		cache.put(contactId, new CachedConversation(limitedHeaders, System.currentTimeMillis()));
	}

	/**
	 * Get cached messages for instant display.
	 * Returns null if no cache or cache is expired.
	 */
	@Nullable
	public List<ConversationMessageHeader> getSnapshot(ContactId contactId) {
		CachedConversation cached = cache.get(contactId);
		if (cached == null) {
			return null;
		}

		// Check expiry
		if (System.currentTimeMillis() - cached.timestamp > CACHE_EXPIRY_MS) {
			cache.remove(contactId);
			synchronized (accessOrder) {
				accessOrder.remove(contactId);
			}
			return null;
		}

		// Update access order
		synchronized (accessOrder) {
			accessOrder.remove(contactId);
			accessOrder.add(contactId);
		}

		return new ArrayList<>(cached.headers);
	}

	/**
	 * Check if cache has valid data for a conversation.
	 */
	public boolean hasValidCache(ContactId contactId) {
		CachedConversation cached = cache.get(contactId);
		if (cached == null) return false;
		return System.currentTimeMillis() - cached.timestamp <= CACHE_EXPIRY_MS;
	}

	/**
	 * Add a new message to the cache (for real-time updates).
	 */
	public void addMessage(ContactId contactId, ConversationMessageHeader header) {
		CachedConversation cached = cache.get(contactId);
		if (cached != null) {
			synchronized (cached) {
				// Add to beginning (most recent)
				cached.headers.add(0, header);
				// Trim if over limit
				while (cached.headers.size() > MAX_CACHED_MESSAGES) {
					cached.headers.remove(cached.headers.size() - 1);
				}
				cached.timestamp = System.currentTimeMillis();
			}
		}
	}

	/**
	 * Remove a message from the cache.
	 */
	public void removeMessage(ContactId contactId, org.briarproject.bramble.api.sync.MessageId messageId) {
		CachedConversation cached = cache.get(contactId);
		if (cached != null) {
			synchronized (cached) {
				cached.headers.removeIf(h -> h.getId().equals(messageId));
			}
		}
	}

	/**
	 * Invalidate cache for a conversation (e.g., on significant changes).
	 */
	public void invalidate(ContactId contactId) {
		cache.remove(contactId);
		synchronized (accessOrder) {
			accessOrder.remove(contactId);
		}
	}

	/**
	 * Clear all cached data.
	 */
	public void clearAll() {
		cache.clear();
		synchronized (accessOrder) {
			accessOrder.clear();
		}
	}

	/**
	 * Internal class to hold cached conversation data.
	 */
	private static class CachedConversation {
		final List<ConversationMessageHeader> headers;
		long timestamp;

		CachedConversation(List<ConversationMessageHeader> headers, long timestamp) {
			this.headers = Collections.synchronizedList(new ArrayList<>(headers));
			this.timestamp = timestamp;
		}
	}
}
