package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelMemberPosts {

	static final int MAX_REMEMBERED_SUBMISSIONS = 64;
	private static final long HOUR_MS = 60L * 60L * 1000L;
	private static final long DAY_MS = 24L * HOUR_MS;

	private ChannelMemberPosts() {
	}

	static boolean validMode(long mode) {
		return mode == ChannelConstants.POSTING_EDITORS
				|| mode == ChannelConstants.POSTING_OWNER_ONLY
				|| mode == ChannelConstants.POSTING_EVERYONE;
	}

	static boolean validRole(long role) {
		return role == ChannelConstants.MEMBER_ROLE_SUBSCRIBER;
	}

	static boolean validName(String name) {
		if (name.length() > ChannelConstants.MAX_MEMBER_NAME_CHARS
				|| name.trim().isEmpty()) {
			return false;
		}
		int marks = 0;
		for (int i = 0; i < name.length(); ) {
			int cp = name.codePointAt(i);
			if (!allowed(name, i, cp)) return false;
			marks = combining(cp) ? marks + 1 : 0;
			if (marks > ChannelConstants.MAX_MEMBER_NAME_COMBINING_MARKS) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}

	static String cleanName(String raw) {
		StringBuilder out = new StringBuilder();
		int marks = 0;
		for (int i = 0; i < raw.length(); ) {
			int cp = raw.codePointAt(i);
			i += Character.charCount(cp);
			if (!allowed(raw, i - Character.charCount(cp), cp)) continue;
			marks = combining(cp) ? marks + 1 : 0;
			if (marks > ChannelConstants.MAX_MEMBER_NAME_COMBINING_MARKS) {
				continue;
			}
			out.appendCodePoint(cp);
		}
		String trimmed = out.toString().trim();
		int max = ChannelConstants.MAX_MEMBER_NAME_CHARS;
		if (trimmed.length() <= max) return trimmed;
		int end = Character.isHighSurrogate(trimmed.charAt(max - 1))
				? max - 1 : max;
		return trimmed.substring(0, end).trim();
	}

	private static boolean combining(int cp) {
		int type = Character.getType(cp);
		return type == Character.NON_SPACING_MARK
				|| type == Character.ENCLOSING_MARK
				|| type == Character.COMBINING_SPACING_MARK;
	}

	private static boolean allowed(String s, int at, int cp) {
		if (Character.isSurrogate(s.charAt(at))
				&& Character.charCount(cp) == 1) {
			return false;
		}
		int type = Character.getType(cp);
		if (type == Character.CONTROL
				|| type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR
				|| type == Character.UNASSIGNED
				|| type == Character.PRIVATE_USE) {
			return false;
		}
		if (type == Character.FORMAT) {
			return cp == 0x200D || (cp >= 0xE0020 && cp <= 0xE007F);
		}
		return true;
	}

	static boolean validAuthor(@Nullable ChannelPost.MemberAuthor a) {
		return a != null && ChannelAuthorBinding.wellFormed(
				a.getEd25519PubKey(), a.getMlDsaPubKey())
				&& validName(a.getName()) && validRole(a.getRole());
	}

	static boolean validBody(String body) {
		String trimmed = body.trim();
		return !trimmed.isEmpty()
				&& body.length() <= ChannelConstants.MAX_POST_BODY_CHARS
				&& !ChannelPostMarkers.hasReservedShape(body)
				&& !ChannelPostMarkers.hasReservedShape(trimmed)
				&& !ChannelConstants.DELETED_POST_PLACEHOLDER.equals(trimmed);
	}

	@NotNullByDefault
	static final class Quota {

		final long hour;
		final int hourCount;
		final long day;
		final int dayCount;
		final Map<Long, Long> accepted;

		private Quota(long hour, int hourCount, long day, int dayCount,
				Map<Long, Long> accepted) {
			this.hour = hour;
			this.hourCount = hourCount;
			this.day = day;
			this.dayCount = dayCount;
			this.accepted = accepted;
		}

		static Quota parse(@Nullable String stored, long now) {
			long hour = now / HOUR_MS;
			long day = now / DAY_MS;
			int hourCount = 0;
			int dayCount = 0;
			Map<Long, Long> accepted = new LinkedHashMap<>();
			if (stored != null) {
				String[] parts = stored.split(":", -1);
				try {
					if (parts.length == 5) {
						if (Long.parseLong(parts[0]) == hour) {
							hourCount = Integer.parseInt(parts[1]);
						}
						if (Long.parseLong(parts[2]) == day) {
							dayCount = Integer.parseInt(parts[3]);
						}
						for (String pair : parts[4].split(",")) {
							int eq = pair.indexOf('=');
							if (eq <= 0) continue;
							accepted.put(Long.parseLong(pair.substring(0, eq)),
									Long.parseLong(pair.substring(eq + 1)));
						}
					}
				} catch (NumberFormatException e) {
					hourCount = 0;
					dayCount = 0;
					accepted.clear();
				}
			}
			return new Quota(hour, Math.max(0, hourCount), day,
					Math.max(0, dayCount), accepted);
		}

		@Nullable
		Long acceptedAs(long submissionId) {
			return accepted.get(submissionId);
		}

		boolean full() {
			return full(ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR,
					ChannelConstants.MAX_MEMBER_POSTS_PER_DAY);
		}

		boolean full(int perHour, int perDay) {
			return hourCount >= perHour || dayCount >= perDay;
		}

		String counted() {
			return hour + ":" + (hourCount + 1) + ":" + day + ":"
					+ (dayCount + 1) + ":";
		}

		String after(long submissionId, long seq) {
			List<Map.Entry<Long, Long>> kept =
					new ArrayList<>(accepted.entrySet());
			StringBuilder ids = new StringBuilder();
			int from = Math.max(0,
					kept.size() + 1 - MAX_REMEMBERED_SUBMISSIONS);
			for (int i = from; i < kept.size(); i++) {
				ids.append(kept.get(i).getKey()).append('=')
						.append(kept.get(i).getValue()).append(',');
			}
			ids.append(submissionId).append('=').append(seq);
			return hour + ":" + (hourCount + 1) + ":" + day + ":"
					+ (dayCount + 1) + ":" + ids;
		}
	}
}
