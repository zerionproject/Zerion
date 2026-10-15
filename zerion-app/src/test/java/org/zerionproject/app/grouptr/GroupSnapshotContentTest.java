package org.zerionproject.app.grouptr;

import org.junit.Test;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSnapshotContentTest {

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] memberKey = key((byte) 2);

	@Test
	public void aSnapshotNamesNoMemberAndCarriesNoJoinTime()
			throws Exception {
		GroupTrTestNode creator = new GroupTrTestNode(creatorKey);
		creator.putGroup(groupId, creatorKey, 3L,
				new GroupTrMember(creatorKey, "Creator", 0L, 0L,
						MemberRole.CREATOR),
				new GroupTrMember(memberKey, "Name only the creator uses",
						1_234_567L, 2L));
		creator.addContact(memberKey);

		creator.manager.sendMemberListSnapshot(groupId);

		List<BdfList> snapshots = new ArrayList<>();
		for (GroupTrTestNode.Sent s : creator.sent) {
			if (s.body.getLong(0) == MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT) {
				snapshots.add(s.body);
			}
		}
		assertFalse(snapshots.isEmpty());
		List<String> seen = new ArrayList<>();
		for (BdfList body : snapshots) {
			BdfList members = body.getList(4);
			for (int i = 0; i < members.size(); i++) {
				BdfList m = members.getList(i);
				String who = Arrays.equals(m.getRaw(0), creatorKey)
						? "creator" : "member";
				seen.add(who + " name \"" + m.getString(1) + "\" joined at "
						+ m.getLong(2) + " epoch " + m.getLong(3) + " role "
						+ m.getLong(4));
			}
		}
		assertEquals("[creator name \"\" joined at 0 epoch 0 role "
						+ MemberRole.CREATOR.getInt()
						+ ", member name \"\" joined at 0 epoch 2 role "
						+ MemberRole.MEMBER.getInt() + "]",
				seen.toString());
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
