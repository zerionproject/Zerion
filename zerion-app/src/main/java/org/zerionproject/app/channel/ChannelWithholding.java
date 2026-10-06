package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelWithholding {

	private ChannelWithholding() {
	}

	@Nullable
	static List<ChannelPost> withholdRevoked(ChannelPostValidator validator,
			ChannelState state, List<ChannelPost> posts) {
		List<Long> revoked = newlyRevoked(validator, state, posts);
		if (revoked.isEmpty()) return null;
		List<ChannelPost> out = new ArrayList<>(posts.size());
		for (ChannelPost p : posts) {
			out.add(revoked.contains(p.getSeqNum()) ? p.withheld() : p);
		}
		return out;
	}

	static List<Long> newlyRevoked(ChannelPostValidator validator,
			ChannelState state, List<ChannelPost> posts) {
		List<Long> out = new ArrayList<>();
		for (ChannelPost p : posts) {
			if (!p.isWithheld() && p.signedByDelegate()
					&& validator.validateSigner(state, p)
					== ChannelPostValidator.Result.DELEGATION_REVOKED) {
				out.add(p.getSeqNum());
			}
		}
		return out;
	}

	static int unreadCount(List<ChannelPost> posts) {
		int n = 0;
		for (ChannelPost p : posts) {
			if (!p.isRead() && !p.isWithheld()) n++;
		}
		return n;
	}
}
