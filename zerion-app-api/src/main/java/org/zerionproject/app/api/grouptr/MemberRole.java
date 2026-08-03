package org.zerionproject.app.api.grouptr;

public enum MemberRole {

	MEMBER(0),
	ADMIN(1),
	CREATOR(2);

	private final int value;

	MemberRole(int value) {
		this.value = value;
	}

	public int getInt() {
		return value;
	}

	public static MemberRole valueOf(int v) {
		for (MemberRole r : values()) {
			if (r.value == v) return r;
		}
		return MEMBER;
	}
}
