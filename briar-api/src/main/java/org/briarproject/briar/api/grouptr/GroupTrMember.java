package org.briarproject.briar.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrMember {

	private final byte[] pubKey;
	private final String name;
	private final long joinedAt;
	private final long joinedAtEpoch;

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch) {
		this.pubKey = pubKey;
		this.name = name;
		this.joinedAt = joinedAt;
		this.joinedAtEpoch = joinedAtEpoch;
	}

	public byte[] getPubKey() {
		return pubKey;
	}

	public String getName() {
		return name;
	}

	public long getJoinedAt() {
		return joinedAt;
	}

	public long getJoinedAtEpoch() {
		return joinedAtEpoch;
	}
}
