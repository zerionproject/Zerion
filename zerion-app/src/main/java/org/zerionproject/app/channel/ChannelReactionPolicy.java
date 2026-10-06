package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
final class ChannelReactionPolicy {

	static final ChannelRetention.Limits LIMITS = new ChannelRetention.Limits(
			ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL,
			ChannelConstants.MAX_REACTIONS_PER_POST,
			ChannelConstants.MAX_REACTIONS_PER_CHANNEL,
			ChannelConstants.MAX_REACTION_BYTES_PER_CHANNEL);

	static final ChannelRetention.Limits ANONYMOUS_LIMITS =
			ChannelCommentPolicy.ANONYMOUS_LIMITS;

	static final ChannelRetention.Shape<ChannelReaction> SHAPE =
			new ChannelRetention.Shape<ChannelReaction>() {
				@Override
				public long post(ChannelReaction r) {
					return r.getPostSeqNum();
				}

				@Override
				public String owner(ChannelReaction r) {
					return toHexString(r.getSignerEd25519PubKey());
				}

				@Override
				public long bytes(ChannelReaction r) {
					return storedBytes(r);
				}
			};

	private ChannelReactionPolicy() {
	}

	@Nullable
	static List<ChannelReaction> withAdmitted(List<ChannelReaction> existing,
			ChannelReaction candidate) {
		for (int i = 0; i < existing.size(); i++) {
			ChannelReaction r = existing.get(i);
			if (r.getPostSeqNum() != candidate.getPostSeqNum()
					|| !Arrays.equals(r.getSignerEd25519PubKey(),
					candidate.getSignerEd25519PubKey())) {
				continue;
			}
			if (sameReaction(r, candidate)) return existing;
			if (storedBytes(candidate) > LIMITS.bytes) return null;
			List<ChannelReaction> out = new ArrayList<>(existing);
			out.set(i, candidate);
			return ChannelRetention.fit(out, LIMITS, SHAPE);
		}
		return ChannelRetention.admit(existing, candidate, LIMITS, SHAPE);
	}

	@Nullable
	static List<ChannelReaction> withAdmitted(List<ChannelReaction> existing,
			ChannelReaction candidate, Set<String> known) {
		for (int i = 0; i < existing.size(); i++) {
			ChannelReaction r = existing.get(i);
			if (r.getPostSeqNum() != candidate.getPostSeqNum()
					|| !Arrays.equals(r.getSignerEd25519PubKey(),
					candidate.getSignerEd25519PubKey())) {
				continue;
			}
			if (sameReaction(r, candidate)) return existing;
			if (candidate.getTimestampHourMs() <= r.getTimestampHourMs()) {
				return existing;
			}
			if (storedBytes(candidate) > LIMITS.bytes) return null;
			List<ChannelReaction> out = new ArrayList<>(existing);
			out.set(i, candidate);
			return ChannelRetention.fit(out, LIMITS, SHAPE);
		}
		return ChannelRetention.admit(existing, candidate, LIMITS,
				ANONYMOUS_LIMITS, SHAPE,
				r -> known.contains(SHAPE.owner(r)));
	}

	static long nextTimestamp(@Nullable ChannelReaction held, long nowMs) {
		long hour = 60L * 60L * 1000L;
		long ts = nowMs / hour * hour;
		if (held != null && held.getTimestampHourMs() >= ts) {
			ts = held.getTimestampHourMs() + hour;
		}
		return ts > nowMs + ChannelConstants.ITEM_MAX_FUTURE_MS ? -1L : ts;
	}

	static List<ChannelReaction> fitToCeilings(List<ChannelReaction> rs) {
		return ChannelRetention.fit(rs, LIMITS, SHAPE);
	}

	static List<ChannelReaction> retainPosts(List<ChannelReaction> rs,
			Set<Long> posts) {
		return ChannelRetention.retainPosts(rs, posts, SHAPE);
	}

	static boolean sameReaction(ChannelReaction a, ChannelReaction b) {
		return a.getPostSeqNum() == b.getPostSeqNum()
				&& a.getTimestampHourMs() == b.getTimestampHourMs()
				&& a.getEmoji().equals(b.getEmoji())
				&& Arrays.equals(a.getSignerEd25519PubKey(),
				b.getSignerEd25519PubKey())
				&& Arrays.equals(a.getSignerMlDsaPubKey(),
				b.getSignerMlDsaPubKey())
				&& Arrays.equals(a.getSignature(), b.getSignature());
	}

	static boolean sameSet(List<ChannelReaction> a, List<ChannelReaction> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!sameReaction(a.get(i), b.get(i))) return false;
		}
		return true;
	}

	static long storedBytes(ChannelReaction r) {
		byte[] sig = r.getSignature();
		byte[] ml = r.getSignerMlDsaPubKey();
		return 16L + r.getEmoji().getBytes(StandardCharsets.UTF_8).length
				+ r.getSignerEd25519PubKey().length
				+ (ml == null ? 0 : ml.length) + (sig == null ? 0 : sig.length);
	}

	static long storedBytes(List<ChannelReaction> rs) {
		return ChannelRetention.bytes(rs, SHAPE);
	}
}
