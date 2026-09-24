package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Admission of group invite offers and responses before their signatures
 * are checked: an offer must be timely, come from the contact it names as
 * creator, not target a group we already belong to, not duplicate a pending
 * offer and carry the group id derived from its own fields; a response must
 * come from the contact the invite went to, for a group we still hold.
 */
public class GroupInviteRulesTest {

	private static final long NOW = 1_700_000_000_000L;

	private final byte[] creator = fill((byte) 1);
	private final byte[] self = fill((byte) 2);
	private final byte[] other = fill((byte) 3);
	private final byte[] gid = fill((byte) 9);

	@Test
	public void anOfferMustBeNeitherStaleNorFromTheFuture() {
		assertTrue(GroupTrManagerImpl.inviteOfferTimely(NOW, NOW));
		assertTrue(GroupTrManagerImpl.inviteOfferTimely(NOW,
				NOW + GroupTrManagerImpl.INVITE_OFFER_FUTURE_SKEW_MS));
		assertFalse(GroupTrManagerImpl.inviteOfferTimely(NOW,
				NOW + GroupTrManagerImpl.INVITE_OFFER_FUTURE_SKEW_MS + 1));
		assertTrue(GroupTrManagerImpl.inviteOfferTimely(NOW,
				NOW - GroupTrManagerImpl.INVITE_OFFER_MAX_AGE_MS));
		assertFalse(GroupTrManagerImpl.inviteOfferTimely(NOW,
				NOW - GroupTrManagerImpl.INVITE_OFFER_MAX_AGE_MS - 1));
		assertFalse(GroupTrManagerImpl.inviteOfferTimely(NOW, 0L));
		assertFalse(GroupTrManagerImpl.inviteOfferTimely(NOW,
				Long.MAX_VALUE));
		assertFalse(GroupTrManagerImpl.inviteOfferTimely(NOW,
				Long.MIN_VALUE));
	}

	@Test
	public void anOfferIsAcceptedOnlyFromTheCreatorItNames() {
		assertTrue(GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
				null, self, false, gid, gid));
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(other, creator,
				null, self, false, gid, gid));
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(null, creator,
				null, self, false, gid, gid));
	}

	@Test
	public void anOfferForAGroupWeAlreadyBelongToIsIgnored() {
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
				group(false, self), self, false, gid, gid));
		assertTrue("a dissolved group may be re-offered",
				GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
						group(true, self), self, false, gid, gid));
		assertTrue("a group we are not in may be offered",
				GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
						group(false, other), self, false, gid, gid));
	}

	@Test
	public void aDuplicateOfferAndAMisderivedGroupIdAreIgnored() {
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
				null, self, true, gid, gid));
		byte[] misderived = gid.clone();
		misderived[0] ^= 1;
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(creator, creator,
				null, self, false, misderived, gid));
	}

	@Test
	public void aResponseIsAcceptedOnlyFromTheInvitedContact() {
		GroupTrState g = group(false, other);
		assertTrue(GroupTrManagerImpl.inviteResponseAdmissible(other, other,
				g));
		assertFalse(GroupTrManagerImpl.inviteResponseAdmissible(other, self,
				g));
		assertFalse(GroupTrManagerImpl.inviteResponseAdmissible(other, null,
				g));
		assertFalse(GroupTrManagerImpl.inviteResponseAdmissible(null, other,
				g));
		assertFalse(GroupTrManagerImpl.inviteResponseAdmissible(other, other,
				null));
	}

	private GroupTrState group(boolean dissolved, byte[] member) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(member, "Member", 0L, 1L));
		return new GroupTrState(gid, "g", fill((byte) 8), creator,
				"Creator", 0L, 5L, dissolved, members);
	}

	private static byte[] fill(byte v) {
		byte[] b = new byte[32];
		Arrays.fill(b, v);
		return b;
	}
}
