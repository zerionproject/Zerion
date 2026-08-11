package com.professor.zerion.android.conversation;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationRequest;
import org.zerionproject.app.api.grouptr.GroupTrInvitationHeader;
import org.zerionproject.app.api.sharing.InvitationRequest;
import org.zerionproject.app.api.sharing.Shareable;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationRequestItem extends ConversationNoticeItem {

	enum RequestType {INTRODUCTION, FORUM, BLOG, GROUP, GROUPTR}

	@Nullable
	private final GroupId requestedGroupId;
	private final RequestType requestType;
	@Nullable
	private final SessionId sessionId;
	private final boolean canBeOpened;
	@Nullable
	private final byte[] grouptrGid;
	private boolean answered;

	ConversationRequestItem(@LayoutRes int layoutRes, String text,
			LiveData<String> contactName, RequestType type,
			ConversationRequest<?> r) {
		super(layoutRes, text, contactName, r);
		this.requestType = type;
		this.sessionId = r.getSessionId();
		this.answered = r.wasAnswered();
		this.grouptrGid = null;
		if (r instanceof InvitationRequest) {
			this.requestedGroupId = ((Shareable) r.getNameable()).getId();
			this.canBeOpened = ((InvitationRequest<?>) r).canBeOpened();
		} else {
			this.requestedGroupId = null;
			this.canBeOpened = false;
		}
	}

	ConversationRequestItem(@LayoutRes int layoutRes, String text,
			LiveData<String> contactName, GroupTrInvitationHeader h,
			byte[] grouptrGid, boolean answered) {
		super(layoutRes, text, contactName, h);
		this.requestType = RequestType.GROUPTR;
		this.sessionId = null;
		this.requestedGroupId = null;
		this.canBeOpened = answered;
		this.grouptrGid = grouptrGid;
		this.answered = answered;
	}

	RequestType getRequestType() {
		return requestType;
	}

	@Nullable
	SessionId getSessionId() {
		return sessionId;
	}

	@Nullable
	GroupId getRequestedGroupId() {
		return requestedGroupId;
	}

	@Nullable
	byte[] getGrouptrGid() {
		return grouptrGid;
	}

	boolean wasAnswered() {
		return answered;
	}

	void setAnswered() {
		this.answered = true;
	}

	boolean canBeOpened() {
		return canBeOpened;
	}

}
