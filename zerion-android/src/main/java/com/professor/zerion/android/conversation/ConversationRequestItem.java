package com.professor.zerion.android.conversation;

import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.conversation.ConversationRequest;
import org.zerionproject.app.api.grouptr.GroupTrInvitationHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationRequestItem extends ConversationNoticeItem {

	enum RequestType {INTRODUCTION, GROUPTR, GROUPTR_MEMBER}

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
		this.canBeOpened = false;
	}

	ConversationRequestItem(@LayoutRes int layoutRes, String text,
			LiveData<String> contactName, GroupTrInvitationHeader h,
			byte[] grouptrGid, boolean answered) {
		super(layoutRes, text, contactName, h);
		this.requestType = h.isMemberInvite()
				? RequestType.GROUPTR_MEMBER : RequestType.GROUPTR;
		this.sessionId = null;
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
	byte[] getGrouptrGid() {
		return grouptrGid;
	}

	boolean wasAnswered() {
		return answered;
	}

	void setAnswered() {
		this.answered = true;
	}

	void setAnswered(boolean answered) {
		this.answered = answered;
	}

	boolean canBeOpened() {
		return canBeOpened;
	}

	@Override
	int updateFrom(ConversationItem fresh) {
		int change = super.updateFrom(fresh);
		boolean now = ((ConversationRequestItem) fresh).answered;
		if (answered != now) change = CONTENT_CHANGED;
		answered = now;
		return change;
	}

	@Override
	boolean showsTheSameAs(ConversationItem o) {
		if (!super.showsTheSameAs(o)) return false;
		ConversationRequestItem r = (ConversationRequestItem) o;
		return requestType == r.requestType && answered == r.answered
				&& canBeOpened == r.canBeOpened;
	}

}
