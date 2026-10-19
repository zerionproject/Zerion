package com.professor.zerion.android.channel;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelSettingsRows {

	enum Row {
		MUTE, SHARE, SEND, WHO_CAN_JOIN, RENEW, COMMENTS, WHO_CAN_POST,
		ANNOUNCE, CONNECTION, WAITING, SUBSCRIBERS, EDITORS, BLOCKED, DELETE,
		LEAVE
	}

	enum Section {
		CHANNEL(Row.MUTE),
		ACCESS(Row.SHARE, Row.SEND, Row.WHO_CAN_JOIN, Row.RENEW),
		PARTICIPATION(Row.COMMENTS, Row.WHO_CAN_POST, Row.ANNOUNCE),
		AVAILABILITY(Row.CONNECTION),
		MEMBERS(Row.WAITING, Row.SUBSCRIBERS, Row.EDITORS, Row.BLOCKED),
		ADMINISTRATION(Row.DELETE, Row.LEAVE);

		final List<Row> rows;

		Section(Row... rows) {
			this.rows = Collections.unmodifiableList(Arrays.asList(rows));
		}
	}

	private ChannelSettingsRows() {
	}

	static Set<Row> visible(boolean owner, boolean publicChannel,
			boolean requiresApproval, int blocked) {
		Set<Row> rows = EnumSet.of(Row.MUTE, Row.SHARE, Row.SEND,
				Row.CONNECTION);
		if (!owner) {
			rows.add(Row.ANNOUNCE);
			rows.add(Row.LEAVE);
			return rows;
		}
		rows.add(Row.WHO_CAN_JOIN);
		if (!publicChannel) rows.add(Row.RENEW);
		rows.add(Row.COMMENTS);
		rows.add(Row.WHO_CAN_POST);
		if (!publicChannel && requiresApproval) rows.add(Row.WAITING);
		rows.add(Row.SUBSCRIBERS);
		rows.add(Row.EDITORS);
		if (blocked > 0) rows.add(Row.BLOCKED);
		rows.add(Row.DELETE);
		return rows;
	}

	static List<Section> sections(Set<Row> visible) {
		List<Section> out = new ArrayList<>();
		for (Section s : Section.values()) {
			for (Row r : s.rows) {
				if (visible.contains(r)) {
					out.add(s);
					break;
				}
			}
		}
		return out;
	}
}
