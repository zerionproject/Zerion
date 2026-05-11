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
	private final MemberRole role;

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch) {
		this(pubKey, name, joinedAt, joinedAtEpoch, MemberRole.MEMBER);
	}

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch, MemberRole role) {
		this.pubKey = pubKey;
		this.name = name;
		this.joinedAt = joinedAt;
		this.joinedAtEpoch = joinedAtEpoch;
		this.role = role;
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

	public MemberRole getRole() {
		return role;
	}

	public GroupTrMember withRole(MemberRole r) {
		return new GroupTrMember(pubKey, name, joinedAt, joinedAtEpoch, r);
	}
}
