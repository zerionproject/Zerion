package org.zerionproject.app.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPostDelivery;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
final class GroupTrPostDeliveries {

	@Immutable
	@NotNullByDefault
	static final class Copy {

		final String postId;
		final long epoch;
		final String member;
		final boolean sent;
		final boolean acknowledged;

		Copy(String postId, long epoch, String member, boolean sent,
				boolean acknowledged) {
			this.postId = postId;
			this.epoch = epoch;
			this.member = member;
			this.sent = sent;
			this.acknowledged = acknowledged;
		}
	}

	private static final class Tally {

		long epoch;
		boolean allSent = true;
		boolean allAcknowledged = true;
		final Set<String> acknowledgedBy = new HashSet<>();
	}

	private GroupTrPostDeliveries() {
	}

	static Map<String, GroupTrPostDelivery> of(Collection<Copy> copies,
			Collection<GroupTrMember> others) {
		Map<String, Tally> tallies = new LinkedHashMap<>();
		for (Copy c : copies) {
			Tally t = tallies.get(c.postId);
			if (t == null) {
				t = new Tally();
				t.epoch = c.epoch;
				tallies.put(c.postId, t);
			}
			if (!c.sent) t.allSent = false;
			if (c.acknowledged) t.acknowledgedBy.add(c.member);
			else t.allAcknowledged = false;
		}
		Map<String, GroupTrPostDelivery> out = new HashMap<>();
		for (Map.Entry<String, Tally> e : tallies.entrySet()) {
			Tally t = e.getValue();
			GroupTrPostDelivery d;
			if (t.allAcknowledged && everyMemberAcknowledged(t, others)) {
				d = GroupTrPostDelivery.DELIVERED;
			} else if (t.allSent) {
				d = GroupTrPostDelivery.SENT;
			} else {
				d = GroupTrPostDelivery.WAITING;
			}
			out.put(e.getKey(), d);
		}
		return out;
	}

	private static boolean everyMemberAcknowledged(Tally t,
			Collection<GroupTrMember> others) {
		for (GroupTrMember m : others) {
			if (m.getJoinedAtEpoch() > t.epoch) continue;
			if (!t.acknowledgedBy.contains(toHexString(m.getPubKey()))) {
				return false;
			}
		}
		return true;
	}
}
