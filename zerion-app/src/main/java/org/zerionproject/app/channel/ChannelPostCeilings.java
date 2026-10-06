package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
final class ChannelPostCeilings {

	private ChannelPostCeilings() {
	}

	static List<ChannelPost> admit(List<ChannelPost> existing,
			List<ChannelPost> incoming) {
		long count = existing.size();
		long bytes = 0;
		for (ChannelPost p : existing) bytes += storedBytes(p);
		List<ChannelPost> out = new ArrayList<>(incoming.size());
		for (ChannelPost p : incoming) {
			long b = storedBytes(p);
			if (b > ChannelConstants.MAX_SUBSCRIBER_BYTES_PER_POST
					|| count >= ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL
					|| bytes + b > ChannelConstants
					.MAX_SUBSCRIBER_POST_BYTES_PER_CHANNEL) {
				break;
			}
			out.add(p);
			count++;
			bytes += b;
		}
		return out;
	}

	static long storedBytes(ChannelPost p) {
		long n = 64L + utf8(p.getBody()) + p.getPrevHash().length
				+ p.getSignature().length;
		byte[] delegateEd = p.getDelegateSignerEd25519PubKey();
		if (delegateEd != null) n += delegateEd.length;
		byte[] delegateMl = p.getDelegateSignerMlDsaPubKey();
		if (delegateMl != null) n += delegateMl.length;
		for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
			n += 32L + a.getBlobHash().length + utf8(a.getMimeType())
					+ a.getPerAttachmentKey().length;
			String caption = a.getCaptionUtf8();
			if (caption != null) n += utf8(caption);
			byte[] thumb = a.getThumbnail();
			if (thumb != null) n += thumb.length;
		}
		return n;
	}

	private static long utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}
}
