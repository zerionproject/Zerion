package com.professor.zerion.android.conversation;

import com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat;

import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@NotNullByDefault
final class VoiceMemoParts {

	private static final String SENT = "sent:";
	private static final String RECEIVED = "received:";

	interface Direction {
		boolean isLocal(MessageId m);
	}

	static final class Expansion {

		final Set<MessageId> messages;
		final Set<String> sentMemoIds;

		private Expansion(Set<MessageId> messages, Set<String> sentMemoIds) {
			this.messages = messages;
			this.sentMemoIds = sentMemoIds;
		}
	}

	private VoiceMemoParts() {
	}

	static String key(boolean local, String memoId) {
		return (local ? SENT : RECEIVED) + memoId;
	}

	static boolean isLocalKey(String key) {
		return key.startsWith(SENT);
	}

	static String memoIdOf(String key) {
		return key.substring(isLocalKey(key) ? SENT.length()
				: RECEIVED.length());
	}

	static boolean anyPart(Collection<String> texts) {
		for (String t : texts) {
			if (VoiceMessageChunkFormat.parse(t) != null) return true;
		}
		return false;
	}

	static Expansion expand(Collection<MessageId> deletedIds,
			Map<MessageId, String> deletedTexts,
			Map<MessageId, String> chatTexts, Direction direction) {
		Set<String> keys = new HashSet<>();
		Set<String> sent = new HashSet<>();
		for (Map.Entry<MessageId, String> e : deletedTexts.entrySet()) {
			VoiceMessageChunkFormat.Part p =
					VoiceMessageChunkFormat.parse(e.getValue());
			if (p == null) continue;
			boolean local = direction.isLocal(e.getKey());
			keys.add(key(local, p.memoId));
			if (local) sent.add(p.memoId);
		}
		Set<MessageId> expanded = new HashSet<>(deletedIds);
		for (Map.Entry<MessageId, String> e : chatTexts.entrySet()) {
			VoiceMessageChunkFormat.Part p =
					VoiceMessageChunkFormat.parse(e.getValue());
			if (p == null) continue;
			if (keys.contains(key(direction.isLocal(e.getKey()), p.memoId))) {
				expanded.add(e.getKey());
			}
		}
		return new Expansion(expanded, sent);
	}
}
