package com.professor.zerion.android.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrIncognitoNames;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
final class GroupTrSenderLabel {

	interface DayFormatter {
		String day(long time);
	}

	private GroupTrSenderLabel() {
	}

	static String label(@Nullable String known, @Nullable String chosen,
			String chosenFormat, String unverifiedFormat) {
		String alias = chosen == null ? "" : chosen.trim();
		if (known != null && !known.isEmpty()) {
			return alias.isEmpty() || alias.equals(known) ? known
					: String.format(Locale.ROOT, chosenFormat, known, alias);
		}
		return String.format(Locale.ROOT, unverifiedFormat,
				alias.isEmpty() ? "?" : alias);
	}

	static String label(@Nullable String known, @Nullable String chosen,
			String chosenFormat, String unverifiedFormat,
			String incognitoFormat) {
		if (GroupTrIncognitoNames.isIncognitoName(chosen)) {
			return String.format(Locale.ROOT, incognitoFormat, chosen);
		}
		return label(known, chosen, chosenFormat, unverifiedFormat);
	}

	static Map<String, Long> firstPosts(List<GroupTrPost> posts) {
		Map<String, Long> first = new HashMap<>();
		for (GroupTrPost p : posts) {
			String key = hex(p.getSenderPubKey());
			Long seen = first.get(key);
			if (seen == null || p.getTimestamp() < seen) {
				first.put(key, p.getTimestamp());
			}
		}
		return first;
	}

	static Map<String, String> tellApart(Map<String, String> names,
			List<GroupTrMember> members, Map<String, Long> firstPosts,
			String joinedFormat, String firstWroteFormat,
			DayFormatter days) {
		Set<String> seen = new HashSet<>();
		Set<String> shared = new HashSet<>();
		for (GroupTrMember m : members) {
			String name = names.get(hex(m.getPubKey()));
			if (name != null && !seen.add(name)) shared.add(name);
		}
		Map<String, String> out = new HashMap<>(names);
		for (GroupTrMember m : members) {
			String key = hex(m.getPubKey());
			String name = names.get(key);
			if (name == null || !shared.contains(name)) continue;
			Long wrote = firstPosts.get(key);
			if (m.getJoinedAt() > 0L) {
				out.put(key, String.format(Locale.ROOT, joinedFormat, name,
						days.day(m.getJoinedAt())));
			} else if (wrote != null) {
				out.put(key, String.format(Locale.ROOT, firstWroteFormat,
						name, days.day(wrote)));
			}
		}
		return out;
	}

	private static String hex(byte[] key) {
		return org.zerionproject.core.util.StringUtils.toHexString(key);
	}
}
