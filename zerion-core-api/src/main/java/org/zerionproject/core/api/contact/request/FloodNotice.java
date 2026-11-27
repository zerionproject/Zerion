package org.zerionproject.core.api.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class FloodNotice {

	private final byte[] linkId;
	private final int kind;
	@Nullable
	private final String label;
	private final long at, pausedUntil;

	public FloodNotice(byte[] linkId, int kind, @Nullable String label,
			long at) {
		this(linkId, kind, label, at, 0);
	}

	public FloodNotice(byte[] linkId, int kind, @Nullable String label,
			long at, long pausedUntil) {
		this.linkId = linkId.clone();
		this.kind = kind;
		this.label = label;
		this.at = at;
		this.pausedUntil = pausedUntil;
	}

	public byte[] getLinkId() {
		return linkId.clone();
	}

	public int getKind() {
		return kind;
	}

	@Nullable
	public String getLabel() {
		return label;
	}

	public long getAt() {
		return at;
	}

	public long getPausedUntil() {
		return pausedUntil;
	}
}
