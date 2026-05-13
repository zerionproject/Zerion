package com.professor.zerion.android.conversation;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.grouptr.GroupTrInvitationHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationGroupTrInvitationItem extends ConversationNoticeItem {

	private final GroupId groupTrGroupId;
	private final String groupName;

	ConversationGroupTrInvitationItem(@LayoutRes int layoutRes, String text,
			LiveData<String> contactName, GroupTrInvitationHeader h) {
		super(layoutRes, text, contactName, h);
		this.groupTrGroupId = h.getGroupTrGroupId();
		this.groupName = h.getGroupName();
	}

	GroupId getGroupTrGroupId() {
		return groupTrGroupId;
	}

	String getGroupName() {
		return groupName;
	}
}
