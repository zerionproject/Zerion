package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ChannelQueuedItem {

	public enum Kind {
		COMMENT,
		REACTION,
		ANNOUNCE,
		POST,
		EDIT,
		DELETE,
		COMMENTS_SETTING
	}

	public enum Reason {
		NONE,
		REFUSED,
		NO_ACCESS,
		EXPIRED,
		TOO_MANY_TRIES
	}

	public enum DeleteResult {
		DELETED,
		SENT,
		GONE
	}

	private final long id;
	private final Kind kind;
	private final long postSeqNum;
	private final String text;
	private final boolean commentsOn;
	private final long queuedAtMs;
	private final boolean notSent;
	private final Reason reason;
	private final long ttlSeconds;

	public ChannelQueuedItem(long id, Kind kind, long postSeqNum,
			String text, boolean commentsOn, long queuedAtMs,
			boolean notSent) {
		this(id, kind, postSeqNum, text, commentsOn, queuedAtMs, notSent,
				notSent ? Reason.REFUSED : Reason.NONE);
	}

	public ChannelQueuedItem(long id, Kind kind, long postSeqNum,
			String text, boolean commentsOn, long queuedAtMs,
			boolean notSent, Reason reason) {
		this(id, kind, postSeqNum, text, commentsOn, queuedAtMs, notSent,
				reason, 0L);
	}

	public ChannelQueuedItem(long id, Kind kind, long postSeqNum,
			String text, boolean commentsOn, long queuedAtMs,
			boolean notSent, Reason reason, long ttlSeconds) {
		this.id = id;
		this.kind = kind;
		this.postSeqNum = postSeqNum;
		this.text = text;
		this.commentsOn = commentsOn;
		this.queuedAtMs = queuedAtMs;
		this.notSent = notSent;
		this.reason = reason;
		this.ttlSeconds = ttlSeconds;
	}

	public long getId() {
		return id;
	}

	public Kind getKind() {
		return kind;
	}

	public long getPostSeqNum() {
		return postSeqNum;
	}

	public String getText() {
		return text;
	}

	public boolean isCommentsOn() {
		return commentsOn;
	}

	public long getQueuedAtMs() {
		return queuedAtMs;
	}

	public boolean isNotSent() {
		return notSent;
	}

	public Reason getReason() {
		return reason;
	}

	public long getTtlSeconds() {
		return ttlSeconds;
	}
}
