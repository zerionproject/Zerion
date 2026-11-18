package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class ZppLinkParams {

	static final int VERSION = 1;
	static final int FLAG_SPARSE_TOLERANT = 0x01;
	static final int FLAG_COVER_WANTED = 0x02;
	static final int LENGTH = 2;

	final boolean sparseTolerant;
	final boolean coverWanted;

	ZppLinkParams(boolean sparseTolerant, boolean coverWanted) {
		this.sparseTolerant = sparseTolerant;
		this.coverWanted = coverWanted;
	}

	public static byte[] encode(boolean coverWanted) {
		int flags = FLAG_SPARSE_TOLERANT;
		if (coverWanted) flags |= FLAG_COVER_WANTED;
		return new byte[] {(byte) VERSION, (byte) flags};
	}

	@Nullable
	public static ZppLinkParams decode(byte[] payload) {
		if (payload.length < LENGTH) return null;
		int version = payload[0] & 0xFF;
		if (version < VERSION) return null;
		int flags = payload[1] & 0xFF;
		return new ZppLinkParams((flags & FLAG_SPARSE_TOLERANT) != 0,
				(flags & FLAG_COVER_WANTED) != 0);
	}
}
