package com.professor.zerion.android.grouptr;

import org.junit.Test;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupTrPinsTest {

	private final byte[] creator = key((byte) 1);
	private final byte[] admin = key((byte) 2);
	private final byte[] member = key((byte) 3);

	@Test
	public void onlyTheGroupOwnerMayPin() {
		GroupTrState s = group(false);
		assertTrue(GroupTrPins.mayPin(s, creator));
		assertFalse("an admin has no pin right", GroupTrPins.mayPin(s, admin));
		assertFalse(GroupTrPins.mayPin(s, member));
		assertFalse(GroupTrPins.mayPin(s, key((byte) 9)));
	}

	@Test
	public void nobodyPinsInADissolvedGroupOrWithoutAState() {
		assertFalse(GroupTrPins.mayPin(group(true), creator));
		assertFalse(GroupTrPins.mayPin(null, creator));
		assertFalse(GroupTrPins.mayPin(group(false), null));
	}

	private GroupTrState group(boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creator, "Owner", 0L, 0L,
				MemberRole.CREATOR, null));
		members.add(new GroupTrMember(admin, "Admin", 0L, 0L,
				MemberRole.ADMIN, null));
		members.add(new GroupTrMember(member, "Member", 0L, 0L,
				MemberRole.MEMBER, null));
		return new GroupTrState(getRandomId(), "group", new byte[32], creator,
				"Owner", 0L, 1L, dissolved, members);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
