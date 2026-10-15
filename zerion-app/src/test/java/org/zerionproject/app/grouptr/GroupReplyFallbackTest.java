package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupReplyFallbackTest {

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] memberKey = key((byte) 3);
	private final byte[] target = getRandomId();

	private GroupTrTestNode node;
	private ContactId toCreator;
	private ContactId toMember;

	@Before
	public void setUp() {
		node = new GroupTrTestNode(localKey);
		toCreator = node.addContact(creatorKey);
		toMember = node.addContact(memberKey);
		node.putGroup(groupId, creatorKey, 1L, creator(creatorKey),
				member(localKey, 0L), member(memberKey, 0L));
	}

	@Test
	public void aReplyCarriesItsTargetWhenEveryMemberCanShowIt()
			throws Exception {
		node.manager.sendGroupReply(groupId, target, "agreed", 0L);

		assertEquals(2, node.sent.size());
		for (GroupTrTestNode.Sent s : node.sent) {
			byte[] body = s.body.getRaw(5);
			assertEquals(GroupTrBody.Kind.REPLY, GroupTrBody.kindOf(body));
			assertArrayEquals(target, GroupTrBody.replyTarget(body));
			assertEquals("agreed", GroupTrBody.parse(body).text);
		}
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertArrayEquals(target, GroupTrBody.replyTarget(
				shown.get(0).getBody()));
	}

	@Test
	public void aReplyIsSentAsPlainTextWhenAMemberRunsAnOlderApp()
			throws Exception {
		node.announceMinorVersion(toMember,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION - 1);

		node.manager.sendGroupReply(groupId, target, "agreed", 0L);

		assertEquals(2, node.sent.size());
		for (GroupTrTestNode.Sent s : node.sent) {
			BdfList record = s.body;
			assertArrayEquals("agreed".getBytes(StandardCharsets.UTF_8),
					record.getRaw(5));
		}
		assertEquals(toCreator, node.sent.get(0).to);
	}

	@Test
	public void aMemberWhoseVersionIsUnknownGetsPlainText() throws Exception {
		node.announceMinorVersion(toCreator, -1);

		node.manager.sendGroupReply(groupId, target, "agreed", 0L);

		for (GroupTrTestNode.Sent s : node.sent) {
			assertEquals(GroupTrBody.Kind.TEXT,
					GroupTrBody.kindOf(s.body.getRaw(5)));
		}
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
