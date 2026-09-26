package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Withholding of stored posts after a delegation is revoked. A withheld post
 * keeps its bytes and its place in the chain and loses only its visibility;
 * the unread count then counts posts that are neither read nor withheld.
 */
@NotNullByDefault
final class ChannelWithholding {

	private ChannelWithholding() {
	}

	/**
	 * Re-judges every visible delegate-signed post against the given state
	 * and returns the list with those the state now reports as revoked
	 * marked withheld, or null when no post changed. Judging the stored
	 * post with the same validator a late subscriber would use keeps the
	 * two views of the channel identical.
	 */
	@Nullable
	static List<ChannelPost> withholdRevoked(ChannelPostValidator validator,
			ChannelState state, List<ChannelPost> posts) {
		List<ChannelPost> out = new ArrayList<>(posts.size());
		boolean changed = false;
		ChannelPost prev = null;
		for (ChannelPost p : posts) {
			ChannelPost kept = p;
			if (!p.isWithheld() && p.signedByDelegate()
					&& validator.validate(state, p, prev)
					== ChannelPostValidator.Result.DELEGATION_REVOKED) {
				kept = p.withheld();
				changed = true;
			}
			out.add(kept);
			prev = p;
		}
		return changed ? out : null;
	}

	static int unreadCount(List<ChannelPost> posts) {
		int n = 0;
		for (ChannelPost p : posts) {
			if (!p.isRead() && !p.isWithheld()) n++;
		}
		return n;
	}
}
