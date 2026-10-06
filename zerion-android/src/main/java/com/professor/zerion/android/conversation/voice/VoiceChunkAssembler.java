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

@Singleton
@NotNullByDefault
public class VoiceChunkAssembler {

	static final int MAX_ASSEMBLIES = 32;
	static final int MAX_SCOPES = 128;

	private final Object lock = new Object();
	private final Map<String, Scope> scopes =
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

	private static String scopeKey(ContactId contactId, boolean local) {
		return contactId.getInt() + (local ? ":sent" : ":received");
	}

	private Scope scope(ContactId contactId, boolean local) {
		String key = scopeKey(contactId, local);
		Scope s = scopes.get(key);
		if (s == null) {
			s = new Scope();
			scopes.put(key, s);
			while (scopes.size() > MAX_SCOPES) {
				Iterator<String> it = scopes.keySet().iterator();
				it.next();
				it.remove();
			}
		}
		return s;
	}

	@Nullable
	private Scope existingScope(ContactId contactId, boolean local) {
		return scopes.get(scopeKey(contactId, local));
	}

	public void putComplete(ContactId contactId, String memoId,
			String fullVoiceText) {
		int durationMs = VoiceMessageFormat.extractDuration(fullVoiceText);
		synchronized (lock) {
			Scope s = scope(contactId, true);
			Assembly a = new Assembly(1, durationMs);
			a.reassembled = fullVoiceText;
			a.received = 1;
			s.assemblies.put(memoId, a);
			s.evictIfNeeded();
		}
	}

	public void addPartText(ContactId contactId, boolean local,
			@Nullable String partText) {
		VoiceMessageChunkFormat.Part p = VoiceMessageChunkFormat.parse(partText);
		if (p == null) return;
		synchronized (lock) {
			Scope s = scope(contactId, local);
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
	public String getReassembled(ContactId contactId, boolean local,
			String memoId) {
		synchronized (lock) {
			Scope s = existingScope(contactId, local);
			if (s == null) return null;
			Assembly a = s.assemblies.get(memoId);
			return a == null ? null : a.reassembled;
		}
	}

	public boolean isFailed(ContactId contactId, boolean local,
			String memoId) {
		synchronized (lock) {
			Scope s = existingScope(contactId, local);
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
