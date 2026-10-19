package com.professor.zerion.android.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;

import java.util.Arrays;

import javax.annotation.Nullable;

import androidx.annotation.VisibleForTesting;

import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
final class GroupPostRow {

	final GroupTrPost post;
	final String key;
	final GroupTrBody.Kind kind;
	final boolean pending;
	private final int bodyLength;
	@Nullable
	private volatile GroupTrBody.Parsed parsed;

	private GroupPostRow(GroupTrPost post) {
		this.post = post;
		this.key = key(post);
		this.pending = post instanceof PendingGroupPost;
		byte[] body = post.getBody();
		this.kind = GroupTrBody.kindOf(body);
		this.bodyLength = body == null ? 0 : body.length;
		if (kind == GroupTrBody.Kind.TEXT || kind == GroupTrBody.Kind.REPLY) {
			parsed = GroupTrBody.parse(body);
		}
	}

	static GroupPostRow of(GroupTrPost post) {
		return new GroupPostRow(post);
	}

	static String key(GroupTrPost p) {
		if (p instanceof PendingGroupPost) return ((PendingGroupPost) p).key();
		byte[] id = p.getPostId();
		if (id != null) return toHexString(id);
		return p.getEpoch() + ":" + p.getTimestamp() + ":"
				+ toHexString(p.getSenderPubKey());
	}

	GroupTrBody.Parsed parsed() {
		GroupTrBody.Parsed p = parsed;
		if (p != null) return p;
		p = GroupTrBody.parse(post.getBody());
		if (p.payload == null) parsed = p;
		return p;
	}

	@VisibleForTesting
	boolean retainsPayload() {
		GroupTrBody.Parsed p = parsed;
		return p != null && p.payload != null;
	}

	@Nullable
	String text() {
		return kind == GroupTrBody.Kind.TEXT || kind == GroupTrBody.Kind.REPLY
				? parsed().text : null;
	}

	@Nullable
	byte[] replyTo() {
		return kind == GroupTrBody.Kind.REPLY ? parsed().replyTo : null;
	}

	boolean isStored() {
		return !pending && post.getPostId() != null;
	}

	boolean shows(GroupTrPost p) {
		if (pending != (p instanceof PendingGroupPost)) return false;
		if (!key.equals(key(p))) return false;
		byte[] body = p.getBody();
		if (bodyLength != (body == null ? 0 : body.length)) return false;
		if (pending) {
			return ((PendingGroupPost) post).sameShown((PendingGroupPost) p);
		}
		return post.getEpoch() == p.getEpoch()
				&& post.getTimestamp() == p.getTimestamp()
				&& post.getTimerStart() == p.getTimerStart()
				&& post.getAutoDeleteTimerMs() == p.getAutoDeleteTimerMs()
				&& post.isLocal() == p.isLocal()
				&& post.getSenderName().equals(p.getSenderName())
				&& Arrays.equals(post.getSenderPubKey(), p.getSenderPubKey());
	}

	boolean sameRow(GroupPostRow other) {
		return pending == other.pending && key.equals(other.key);
	}

	boolean sameShown(GroupPostRow other) {
		return this == other || shows(other.post);
	}
}
