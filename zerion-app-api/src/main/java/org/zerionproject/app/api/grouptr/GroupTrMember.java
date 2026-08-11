package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrMember {

	private final byte[] pubKey;
	private final String name;
	private final long joinedAt;
	private final long joinedAtEpoch;
	private final MemberRole role;
	@Nullable
	private final byte[] mlDsaPubKey;

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch) {
		this(pubKey, name, joinedAt, joinedAtEpoch, MemberRole.MEMBER, null);
	}

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch, MemberRole role) {
		this(pubKey, name, joinedAt, joinedAtEpoch, role, null);
	}

	public GroupTrMember(byte[] pubKey, String name, long joinedAt,
			long joinedAtEpoch, MemberRole role,
			@Nullable byte[] mlDsaPubKey) {
		this.pubKey = pubKey;
		this.name = name;
		this.joinedAt = joinedAt;
		this.joinedAtEpoch = joinedAtEpoch;
		this.role = role;
		this.mlDsaPubKey = mlDsaPubKey;
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

	@Nullable
	public byte[] getMlDsaPubKey() {
		return mlDsaPubKey;
	}

	public GroupTrMember withRole(MemberRole r) {
		return new GroupTrMember(pubKey, name, joinedAt, joinedAtEpoch, r,
				mlDsaPubKey);
	}
}
