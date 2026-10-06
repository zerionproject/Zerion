package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
final class ChannelCommentPolicy {

	static final ChannelRetention.Limits LIMITS = new ChannelRetention.Limits(
			ChannelConstants.MAX_COMMENTS_PER_AUTHOR,
			ChannelConstants.MAX_COMMENTS_PER_POST,
			ChannelConstants.MAX_COMMENTS_PER_CHANNEL,
			ChannelConstants.MAX_COMMENT_BYTES_PER_CHANNEL);

	static final ChannelRetention.Limits ANONYMOUS_LIMITS =
			new ChannelRetention.Limits(
					ChannelConstants.MAX_ANONYMOUS_ITEMS_PER_SIGNER,
					ChannelConstants.MAX_ANONYMOUS_ITEMS_PER_POST,
					ChannelConstants.MAX_ANONYMOUS_ITEMS_PER_CHANNEL,
					ChannelConstants.MAX_ANONYMOUS_ITEM_BYTES_PER_CHANNEL);

	static final ChannelRetention.Shape<ChannelComment> SHAPE =
			new ChannelRetention.Shape<ChannelComment>() {
				@Override
				public long post(ChannelComment c) {
					return c.getParentPostSeqNum();
				}

				@Override
				public String owner(ChannelComment c) {
					return toHexString(c.getAuthorEd25519PubKey());
				}

				@Override
				public long bytes(ChannelComment c) {
					return storedBytes(c);
				}
			};

	private ChannelCommentPolicy() {
	}

	static boolean validFields(String body, String authorName) {
		return !body.isEmpty()
				&& body.length() <= ChannelConstants.MAX_COMMENT_BODY_CHARS
				&& authorName.length()
				<= ChannelConstants.MAX_COMMENT_AUTHOR_NAME_CHARS;
	}

	static boolean validFields(ChannelComment c) {
		return validFields(c.getBody(), c.getAuthorDisplayName());
	}

	@Nullable
	static List<ChannelComment> withAdmitted(List<ChannelComment> existing,
			ChannelComment candidate) {
		if (!validFields(candidate)) return null;
		for (ChannelComment c : existing) {
			if (c.getCommentId() != candidate.getCommentId()) continue;
			return sameComment(c, candidate) ? existing : null;
		}
		return ChannelRetention.admit(existing, candidate, LIMITS, SHAPE);
	}

	@Nullable
	static List<ChannelComment> withAdmitted(List<ChannelComment> existing,
			ChannelComment candidate, Set<String> known) {
		if (!validFields(candidate)) return null;
		for (ChannelComment c : existing) {
			if (c.getCommentId() != candidate.getCommentId()) continue;
			return sameComment(c, candidate) ? existing : null;
		}
		return ChannelRetention.admit(existing, candidate, LIMITS,
				ANONYMOUS_LIMITS, SHAPE,
				c -> known.contains(SHAPE.owner(c)));
	}

	static List<ChannelComment> fitToCeilings(List<ChannelComment> cs) {
		List<ChannelComment> valid = new ArrayList<>(cs.size());
		Set<Long> ids = new HashSet<>();
		for (int i = cs.size() - 1; i >= 0; i--) {
			ChannelComment c = cs.get(i);
			if (validFields(c) && ids.add(c.getCommentId())) valid.add(c);
		}
		if (valid.size() == cs.size()) {
			return ChannelRetention.fit(cs, LIMITS, SHAPE);
		}
		java.util.Collections.reverse(valid);
		return ChannelRetention.fit(valid, LIMITS, SHAPE);
	}

	static List<ChannelComment> retainPosts(List<ChannelComment> cs,
			Set<Long> posts) {
		return ChannelRetention.retainPosts(cs, posts, SHAPE);
	}

	static boolean sameComment(ChannelComment a, ChannelComment b) {
		return a.getCommentId() == b.getCommentId()
				&& a.getParentPostSeqNum() == b.getParentPostSeqNum()
				&& a.getTimestampHourMs() == b.getTimestampHourMs()
				&& a.getBody().equals(b.getBody())
				&& a.getAuthorDisplayName().equals(b.getAuthorDisplayName())
				&& Arrays.equals(a.getAuthorEd25519PubKey(),
				b.getAuthorEd25519PubKey())
				&& Arrays.equals(a.getAuthorMlDsaPubKey(),
				b.getAuthorMlDsaPubKey())
				&& Arrays.equals(a.getSignature(), b.getSignature());
	}

	static boolean sameSet(List<ChannelComment> a, List<ChannelComment> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (!sameComment(a.get(i), b.get(i))) return false;
		}
		return true;
	}

	static long storedBytes(ChannelComment c) {
		byte[] sig = c.getSignature();
		byte[] ml = c.getAuthorMlDsaPubKey();
		return 24L + c.getBody().getBytes(StandardCharsets.UTF_8).length
				+ c.getAuthorDisplayName().getBytes(StandardCharsets.UTF_8).length
				+ c.getAuthorEd25519PubKey().length
				+ (ml == null ? 0 : ml.length) + (sig == null ? 0 : sig.length);
	}

	static long storedBytes(List<ChannelComment> cs) {
		return ChannelRetention.bytes(cs, SHAPE);
	}
}
