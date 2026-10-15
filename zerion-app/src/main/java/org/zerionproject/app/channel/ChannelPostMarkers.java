package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelPostMarkers {

	private ChannelPostMarkers() {
	}

	@NotNullByDefault
	static final class Edit {

		final long target;
		final String text;

		Edit(long target, String text) {
			this.target = target;
			this.text = text;
		}
	}

	static String deletion(byte[] channelId, long target) {
		return ChannelConstants.TOMBSTONE_PREFIX + ChannelStore.hex(channelId)
				+ ":" + target + ":D";
	}

	static String edit(byte[] channelId, long target, String text) {
		return ChannelConstants.EDIT_PREFIX + ChannelStore.hex(channelId)
				+ ":" + target + ":" + text;
	}

	@Nullable
	static Long deletionTarget(String body, String channelIdHex) {
		String prefix = ChannelConstants.TOMBSTONE_PREFIX
				+ channelIdHex + ":";
		if (!body.startsWith(prefix)) return null;
		String rest = body.substring(prefix.length());
		int colon = rest.indexOf(':');
		if (colon <= 0) return null;
		try {
			return Long.parseLong(rest.substring(0, colon));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Nullable
	static Edit edit(String body, String channelIdHex) {
		String prefix = ChannelConstants.EDIT_PREFIX + channelIdHex + ":";
		if (!body.startsWith(prefix)) return null;
		String rest = body.substring(prefix.length());
		int colon = rest.indexOf(':');
		if (colon <= 0) return null;
		String number = rest.substring(0, colon);
		for (int i = 0; i < number.length(); i++) {
			if (number.charAt(i) < '0' || number.charAt(i) > '9') return null;
		}
		long target;
		try {
			target = Long.parseLong(number);
		} catch (NumberFormatException e) {
			return null;
		}
		String text = rest.substring(colon + 1);
		if (text.trim().isEmpty()) return null;
		return new Edit(target, text);
	}

	static boolean isMarker(String body, String channelIdHex) {
		return deletionTarget(body, channelIdHex) != null
				|| edit(body, channelIdHex) != null;
	}

	static boolean hasReservedShape(String body) {
		return body.startsWith(ChannelConstants.TOMBSTONE_PREFIX)
				|| body.startsWith(ChannelConstants.EDIT_PREFIX);
	}
}
