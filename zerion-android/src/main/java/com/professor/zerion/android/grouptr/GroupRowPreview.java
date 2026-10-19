package com.professor.zerion.android.grouptr;

import android.content.res.Resources;

import com.professor.zerion.R;
import com.professor.zerion.android.homelist.HomePreview;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrIncognitoNames;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.core.util.StringUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class GroupRowPreview {

	private GroupRowPreview() {
	}

	static String summary(Resources res, GroupTrPostSummary s) {
		switch (s.getKind()) {
			case VOICE:
				return res.getString(R.string.voice_message);
			case IMAGE:
				return res.getString(R.string.grouptr_image);
			case VIDEO:
				return res.getString(R.string.grouptr_video);
			case MEDIA:
				return res.getString(R.string.home_row_attachment);
			default:
				return HomePreview.oneLine(s.getText());
		}
	}

	static Map<String, String> memberNames(GroupTrState s) {
		Map<String, String> names = new HashMap<>();
		for (GroupTrMember m : s.getMembers()) {
			if (!m.getName().isEmpty()) {
				names.put(StringUtils.toHexString(m.getPubKey()), m.getName());
			}
		}
		return names;
	}

	@Nullable
	static String sender(byte[] senderPubKey, String chosenName,
			Map<String, String> members, Map<String, String> contacts,
			@Nullable byte[] me, String you, String incognitoFormat) {
		if (me != null && Arrays.equals(senderPubKey, me)) return you;
		if (GroupTrIncognitoNames.isIncognitoName(chosenName)) {
			return String.format(Locale.ROOT, incognitoFormat, chosenName);
		}
		String key = StringUtils.toHexString(senderPubKey);
		String name = contacts.get(key);
		if (name == null || name.isEmpty()) name = members.get(key);
		return name == null || name.isEmpty() ? null : name;
	}
}
