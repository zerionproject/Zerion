package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ContactLinkInfo {

	private final byte[] linkId;
	private final int kind, state;
	@Nullable
	private final String label, link;
	private final long createdAt, lastSharedAt, pausedUntil;
	private final boolean inboxOpen, powProtected;
	private final int undecided, blocked;

	public ContactLinkInfo(byte[] linkId, int kind, int state,
			@Nullable String label, @Nullable String link, long createdAt,
			long lastSharedAt, boolean inboxOpen, int undecided, int blocked) {
		this(linkId, kind, state, label, link, createdAt, lastSharedAt,
				inboxOpen, undecided, blocked, false);
	}

	public ContactLinkInfo(byte[] linkId, int kind, int state,
			@Nullable String label, @Nullable String link, long createdAt,
			long lastSharedAt, boolean inboxOpen, int undecided, int blocked,
			boolean powProtected) {
		this(linkId, kind, state, label, link, createdAt, lastSharedAt,
				inboxOpen, undecided, blocked, powProtected, 0);
	}

	public ContactLinkInfo(byte[] linkId, int kind, int state,
			@Nullable String label, @Nullable String link, long createdAt,
			long lastSharedAt, boolean inboxOpen, int undecided, int blocked,
			boolean powProtected, long pausedUntil) {
		this.linkId = linkId.clone();
		this.kind = kind;
		this.state = state;
		this.label = label;
		this.link = link;
		this.createdAt = createdAt;
		this.lastSharedAt = lastSharedAt;
		this.inboxOpen = inboxOpen;
		this.undecided = undecided;
		this.blocked = blocked;
		this.powProtected = powProtected;
		this.pausedUntil = pausedUntil;
	}

	public byte[] getLinkId() {
		return linkId.clone();
	}

	public int getKind() {
		return kind;
	}

	public int getState() {
		return state;
	}

	@Nullable
	public String getLabel() {
		return label;
	}

	@Nullable
	public String getLink() {
		return link;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public long getLastSharedAt() {
		return lastSharedAt;
	}

	public boolean isInboxOpen() {
		return inboxOpen;
	}

	public int getUndecidedCount() {
		return undecided;
	}

	public int getBlockedCount() {
		return blocked;
	}

	public boolean isPowProtected() {
		return powProtected;
	}

	public long getPausedUntil() {
		return pausedUntil;
	}

	public boolean isPaused(long now) {
		return pausedUntil > now;
	}
}
