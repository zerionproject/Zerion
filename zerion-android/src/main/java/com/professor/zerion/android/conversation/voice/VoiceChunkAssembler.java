package com.professor.zerion.android.conversation.voice;

import org.zerionproject.core.api.contact.ContactId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Reassembles chunked voice memos. Assemblies are scoped by the contact the
 * parts came from, so a memo id is only meaningful within one conversation
 * and one contact's parts can never displace or complete another contact's
 * memo. Eviction is per contact: a contact that floods fresh memo ids
 * evicts only its own in-progress assemblies. The number of contact scopes
 * is bounded too, least recently used first, which only ever costs memory
 * for a conversation the user has not touched in a long time.
 */
@Singleton
@NotNullByDefault
public class VoiceChunkAssembler {

	static final int MAX_ASSEMBLIES = 32;
	static final int MAX_SCOPES = 64;

	private final Object lock = new Object();
	private final Map<Integer, Scope> scopes =
			new LinkedHashMap<>(16, 0.75f, true);

	private static final class Scope {
		private final Map<String, Assembly> assemblies =
				new LinkedHashMap<>(16, 0.75f, true);

		private void evictIfNeeded() {
			if (assemblies.size() <= MAX_ASSEMBLIES) return;
			Iterator<Map.Entry<String, Assembly>> it =
					assemblies.entrySet().iterator();
			while (it.hasNext() && assemblies.size() > MAX_ASSEMBLIES) {
				Assembly a = it.next().getValue();
				if (a.reassembled != null || a.failed) it.remove();
			}
			it = assemblies.entrySet().iterator();
			while (it.hasNext() && assemblies.size() > MAX_ASSEMBLIES) {
				it.next();
				it.remove();
			}
		}
	}

	private static class Assembly {
		private final int total;
		private final int durationMs;
		private final String[] slices;
		private int received;
		private boolean failed;
		@Nullable
		private String reassembled;

		private Assembly(int total, int durationMs) {
			this.total = total;
			this.durationMs = durationMs;
			this.slices = new String[total];
		}
	}

	@Inject
	VoiceChunkAssembler() {
	}

	private Scope scope(ContactId contactId) {
		Scope s = scopes.get(contactId.getInt());
		if (s == null) {
			s = new Scope();
			scopes.put(contactId.getInt(), s);
			while (scopes.size() > MAX_SCOPES) {
				Iterator<Integer> it = scopes.keySet().iterator();
				it.next();
				it.remove();
			}
		}
		return s;
	}

	@Nullable
	private Scope existingScope(ContactId contactId) {
		return scopes.get(contactId.getInt());
	}

	public void putComplete(ContactId contactId, String memoId,
			String fullVoiceText) {
		int durationMs = VoiceMessageFormat.extractDuration(fullVoiceText);
		synchronized (lock) {
			Scope s = scope(contactId);
			Assembly a = new Assembly(1, durationMs);
			a.reassembled = fullVoiceText;
			a.received = 1;
			s.assemblies.put(memoId, a);
			s.evictIfNeeded();
		}
	}

	public void addPartText(ContactId contactId, @Nullable String partText) {
		VoiceMessageChunkFormat.Part p = VoiceMessageChunkFormat.parse(partText);
		if (p == null) return;
		synchronized (lock) {
			Scope s = scope(contactId);
			Assembly a = s.assemblies.get(p.memoId);
			if (a == null) {
				a = new Assembly(p.total, p.durationMs);
				s.assemblies.put(p.memoId, a);
				s.evictIfNeeded();
			}
			if (a.failed || a.reassembled != null) return;
			if (a.total != p.total || p.seq >= a.slices.length) return;
			if (a.slices[p.seq] != null) return;
			a.slices[p.seq] = p.slice;
			a.received++;
			if (a.received >= a.total) {
				List<String> ordered = Arrays.asList(a.slices);
				try {
					a.reassembled =
						VoiceMessageChunkFormat.reassemble(a.durationMs, ordered);
				} catch (RuntimeException e) {
					a.failed = true;
				}
				Arrays.fill(a.slices, null);
			}
		}
	}

	@Nullable
	public String getReassembled(ContactId contactId, String memoId) {
		synchronized (lock) {
			Scope s = existingScope(contactId);
			if (s == null) return null;
			Assembly a = s.assemblies.get(memoId);
			return a == null ? null : a.reassembled;
		}
	}

	public boolean isFailed(ContactId contactId, String memoId) {
		synchronized (lock) {
			Scope s = existingScope(contactId);
			if (s == null) return false;
			Assembly a = s.assemblies.get(memoId);
			return a != null && a.failed;
		}
	}

	public void clear() {
		synchronized (lock) {
			scopes.clear();
		}
	}

}
