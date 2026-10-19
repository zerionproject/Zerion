package com.professor.zerion.android.grouptr;

import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.PendingSends;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class PendingGroupPost extends GroupTrPost {

	static final long PENDING_EPOCH = -1L;

	@NotNullByDefault
	static final class Send {

		final byte[] body;
		@Nullable
		final byte[] replyTo;
		final String text;

		Send(byte[] body, @Nullable byte[] replyTo, String text) {
			this.body = body;
			this.replyTo = replyTo;
			this.text = text;
		}

		boolean isText() {
			return !text.isEmpty();
		}
	}

	private final PendingSends.Entry entry;
	private final MessageState state;
	private final boolean retryable;
	private final boolean editable;
	private final boolean discardable;

	PendingGroupPost(byte[] groupId, byte[] localPub, String senderName,
			PendingSends.Entry entry, byte[] shownBody) {
		super(groupId, localPub, senderName, shownBody,
				entry.getCreatedAtMs(), PENDING_EPOCH, true, 0L,
				entry.getCreatedAtMs(), null);
		this.entry = entry;
		this.state = entry.getState();
		this.retryable = entry.canRetry();
		this.editable = entry.canEdit();
		this.discardable = entry.canDiscard();
	}

	PendingSends.Entry getEntry() {
		return entry;
	}

	MessageState getState() {
		return state;
	}

	boolean isRetryable() {
		return retryable;
	}

	boolean isEditable() {
		return editable;
	}

	boolean isDiscardable() {
		return discardable;
	}

	String key() {
		return "pending:" + entry.getId();
	}

	boolean sameShown(PendingGroupPost other) {
		return state == other.state && retryable == other.retryable
				&& editable == other.editable
				&& discardable == other.discardable;
	}
}
