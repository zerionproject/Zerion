package com.professor.zerion.android.channel;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@NotNullByDefault
final class ChannelAuthorLabels {

	interface DayFormatter {
		String day(long hourMs);

		String dayAndHour(long hourMs);
	}

	private ChannelAuthorLabels() {
	}

	static Map<String, String> of(List<ChannelComment> comments,
			String unnamed, String firstWroteFormat, DayFormatter days) {
		Map<String, String> names = new LinkedHashMap<>();
		Map<String, Long> firstSeen = new HashMap<>();
		for (ChannelComment c : comments) {
			String key = StringUtils.toHexString(c.getAuthorEd25519PubKey());
			String name = c.getAuthorDisplayName().trim();
			if (!names.containsKey(key)) {
				names.put(key, name.isEmpty() ? unnamed : name);
			}
			Long seen = firstSeen.get(key);
			if (seen == null || c.getTimestampHourMs() < seen) {
				firstSeen.put(key, c.getTimestampHourMs());
			}
		}
		Map<String, List<String>> keysByName = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : names.entrySet()) {
			List<String> keys = keysByName.get(e.getValue());
			if (keys == null) {
				keys = new ArrayList<>();
				keysByName.put(e.getValue(), keys);
			}
			keys.add(e.getKey());
		}
		Map<String, String> out = new HashMap<>(names);
		for (Map.Entry<String, List<String>> e : keysByName.entrySet()) {
			List<String> keys = e.getValue();
			if (keys.size() < 2) continue;
			Map<String, String> byDay = tagged(e.getKey(), keys, firstSeen,
					firstWroteFormat, days, false);
			if (new HashSet<>(byDay.values()).size() < keys.size()) {
				byDay = tagged(e.getKey(), keys, firstSeen, firstWroteFormat,
						days, true);
			}
			out.putAll(byDay);
		}
		return out;
	}

	private static Map<String, String> tagged(String name, List<String> keys,
			Map<String, Long> firstSeen, String format, DayFormatter days,
			boolean withHour) {
		Map<String, String> out = new HashMap<>();
		for (String key : keys) {
			Long since = firstSeen.get(key);
			long at = since == null ? 0L : since;
			out.put(key, String.format(Locale.ROOT, format, name,
					withHour ? days.dayAndHour(at) : days.day(at)));
		}
		return out;
	}

	static String label(Map<String, String> labels, ChannelComment c,
			String unnamed) {
		String label = labels.get(StringUtils.toHexString(
				c.getAuthorEd25519PubKey()));
		if (label != null) return label;
		String name = c.getAuthorDisplayName().trim();
		return name.isEmpty() ? unnamed : name;
	}
}
