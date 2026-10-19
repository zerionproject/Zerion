package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class GroupTrSendResult {

	private final byte[] postId;
	private final int members;
	private final int recipients;
	private final int failed;
	private final int skipped;

	public GroupTrSendResult(byte[] postId, int members, int recipients,
			int failed, int skipped) {
		this.postId = postId;
		this.members = members;
		this.recipients = recipients;
		this.failed = failed;
		this.skipped = skipped;
	}

	public int getMembers() {
		return members;
	}

	public byte[] getPostId() {
		return postId;
	}

	public int getRecipients() {
		return recipients;
	}

	public int getFailed() {
		return failed;
	}

	public int getReached() {
		return recipients - failed;
	}

	public int getSkipped() {
		return skipped;
	}
}
