package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class VoiceChunkAssembler {

	private static final int MAX_ASSEMBLIES = 32;

	private final Object lock = new Object();
	private final Map<String, Assembly> assemblies =
			new LinkedHashMap<>(16, 0.75f, true);

	private void evictIfNeeded() {
		if (assemblies.size() <= MAX_ASSEMBLIES) return;
		java.util.Iterator<Map.Entry<String, Assembly>> it =
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

	public void putComplete(String memoId, String fullVoiceText) {
		int durationMs = VoiceMessageFormat.extractDuration(fullVoiceText);
		synchronized (lock) {
			Assembly a = new Assembly(1, durationMs);
			a.reassembled = fullVoiceText;
			a.received = 1;
			assemblies.put(memoId, a);
			evictIfNeeded();
		}
	}

	public void addPartText(@Nullable String partText) {
		VoiceMessageChunkFormat.Part p = VoiceMessageChunkFormat.parse(partText);
		if (p == null) return;
		synchronized (lock) {
			Assembly a = assemblies.get(p.memoId);
			if (a == null) {
				a = new Assembly(p.total, p.durationMs);
				assemblies.put(p.memoId, a);
				evictIfNeeded();
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
	public String getReassembled(String memoId) {
		synchronized (lock) {
			Assembly a = assemblies.get(memoId);
			return a == null ? null : a.reassembled;
		}
	}

	public boolean isFailed(String memoId) {
		synchronized (lock) {
			Assembly a = assemblies.get(memoId);
			return a != null && a.failed;
		}
	}

	public void clear() {
		synchronized (lock) {
			assemblies.clear();
		}
	}

}
