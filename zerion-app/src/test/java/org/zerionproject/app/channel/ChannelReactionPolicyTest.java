package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChannelReactionPolicyTest {

	private static byte[] signer(int i) {
		byte[] k = new byte[32];
		k[0] = (byte) (i >> 8);
		k[1] = (byte) i;
		return k;
	}

	private static ChannelReaction reaction(long post, int signer) {
		return reaction(post, signer, "+1", 0);
	}

	private static ChannelReaction reaction(long post, int signer,
			String emoji, long ts) {
		return new ChannelReaction(post, emoji, signer(signer), new byte[4],
				ts, new byte[] {(byte) signer, (byte) post});
	}

	private static boolean holds(List<ChannelReaction> rs, long post,
			int signer) {
		for (ChannelReaction r : rs) {
			if (r.getPostSeqNum() == post
					&& java.util.Arrays.equals(r.getSignerEd25519PubKey(),
					signer(signer))) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void anIdenticalReactionChangesNothing() {
		List<ChannelReaction> existing = new ArrayList<>();
		existing.add(reaction(1, 1));
		existing.add(reaction(2, 2));
		assertSame(existing, ChannelReactionPolicy.withAdmitted(existing,
				reaction(2, 2)));
	}

	@Test
	public void aSignersNewReactionReplacesItsLastOneWhereItStands() {
		List<ChannelReaction> existing = new ArrayList<>();
		existing.add(reaction(1, 1));
		existing.add(reaction(1, 2));
		existing.add(reaction(1, 3));
		List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
				existing, reaction(1, 2, "❤", 5));
		assertNotNull(next);
		assertEquals(3, next.size());
		assertEquals("❤", next.get(1).getEmoji());
		assertEquals("nothing superseded is kept", 1,
				count(next, 1, 2));
	}

	@Test
	public void aFullPostTakesInANewReactionAndItsOldestGivesWay() {
		List<ChannelReaction> existing = new ArrayList<>();
		for (int i = 0; i < ChannelConstants.MAX_REACTIONS_PER_POST; i++) {
			existing.add(reaction(1, i));
		}
		existing.add(reaction(2, 5000));
		List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
				existing, reaction(1, 9999));
		assertNotNull(next);
		assertTrue(holds(next, 1, 9999));
		assertFalse("the post's oldest gave way", holds(next, 1, 0));
		assertTrue(holds(next, 1, 1));
		assertTrue("another post is untouched", holds(next, 2, 5000));
		assertEquals(existing.size(), next.size());
	}

	@Test
	public void aSignerAtItsCeilingKeepsItsNewestReactions() {
		List<ChannelReaction> existing = new ArrayList<>();
		int n = ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL;
		for (long p = 1; p <= n; p++) existing.add(reaction(p, 1));
		existing.add(reaction(1, 2));
		List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
				existing, reaction(n + 1, 1));
		assertNotNull(next);
		assertTrue(holds(next, n + 1, 1));
		assertFalse("the signer's oldest gave way", holds(next, 1, 1));
		assertTrue("another signer is untouched", holds(next, 1, 2));
		assertEquals(n, countSigner(next, 1));
	}

	@Test
	public void aFullChannelTakesInANewReactionFromTheFullestPost() {
		List<ChannelReaction> existing = new ArrayList<>();
		int total = ChannelConstants.MAX_REACTIONS_PER_CHANNEL;
		int s = 0;
		existing.add(reaction(100, s++));
		while (existing.size() < total) {
			existing.add(reaction(1 + existing.size() % 8, s++));
		}
		List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
				existing, reaction(200, 99999));
		assertNotNull(next);
		assertEquals(total, next.size());
		assertTrue(holds(next, 200, 99999));
		assertTrue("the single reaction on post 100 is not the one to go",
				holds(next, 100, 0));
	}

	@Test
	public void aNewReactionIsAlwaysTakenInHoweverTheSetWasFilled() {
		List<ChannelReaction> existing = new ArrayList<>();
		int s = 0;
		while (existing.size() < ChannelConstants.MAX_REACTIONS_PER_CHANNEL) {
			existing.add(reaction(1 + s % 4, 10000 + s));
			s++;
		}
		List<ChannelReaction> current = existing;
		for (int legit = 0; legit < 50; legit++) {
			List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
					current, reaction(1 + legit % 4, legit));
			assertNotNull(next);
			assertTrue(holds(next, 1 + legit % 4, legit));
			assertTrue(next.size()
					<= ChannelConstants.MAX_REACTIONS_PER_CHANNEL);
			current = next;
		}
		for (int legit = 0; legit < 50; legit++) {
			assertTrue("the legitimate reactions all stay",
					holds(current, 1 + legit % 4, legit));
		}
	}

	@Test
	public void ageIsTheOrderOfAdmissionNotTheSendersTimestamp() {
		List<ChannelReaction> existing = new ArrayList<>();
		existing.add(reaction(1, 0, "+1", Long.MAX_VALUE / 2));
		for (int i = 1; i < ChannelConstants.MAX_REACTIONS_PER_POST; i++) {
			existing.add(reaction(1, i, "+1", 0));
		}
		List<ChannelReaction> next = ChannelReactionPolicy.withAdmitted(
				existing, reaction(1, 9999));
		assertNotNull(next);
		assertFalse("a far-future timestamp buys no stay",
				holds(next, 1, 0));
		List<ChannelReaction> legacy = new ArrayList<>();
		for (int i = 0; i < 2 * ChannelConstants.MAX_REACTIONS_PER_CHANNEL;
				i++) {
			legacy.add(reaction(1 + i % 16, i, "+1",
					i == 0 ? Long.MAX_VALUE / 2 : i));
		}
		List<ChannelReaction> fitted =
				ChannelReactionPolicy.fitToCeilings(legacy);
		assertFalse(holds(fitted, 1, 0));
		assertTrue(holds(fitted, 1 + (legacy.size() - 1) % 16,
				legacy.size() - 1));
	}

	@Test
	public void reactionsToPostsNotHeldAreDropped() {
		List<ChannelReaction> existing = new ArrayList<>();
		existing.add(reaction(1, 1));
		existing.add(reaction(5, 2));
		existing.add(reaction(2, 3));
		Set<Long> posts = new HashSet<>();
		posts.add(1L);
		posts.add(2L);
		List<ChannelReaction> kept =
				ChannelReactionPolicy.retainPosts(existing, posts);
		assertEquals(2, kept.size());
		assertFalse(holds(kept, 5, 2));
		posts.add(5L);
		assertSame(existing,
				ChannelReactionPolicy.retainPosts(existing, posts));
	}

	@Test
	public void aReactionAboveTheByteCeilingIsNotTakenIn() {
		ChannelReaction huge = new ChannelReaction(1, "+1", signer(1),
				new byte[4], 0,
				new byte[(int) ChannelConstants.MAX_REACTION_BYTES_PER_CHANNEL]);
		assertNull(ChannelReactionPolicy.withAdmitted(
				new ArrayList<ChannelReaction>(), huge));
	}

	private static int count(List<ChannelReaction> rs, long post,
			int signer) {
		int n = 0;
		for (ChannelReaction r : rs) {
			if (r.getPostSeqNum() == post
					&& java.util.Arrays.equals(r.getSignerEd25519PubKey(),
					signer(signer))) {
				n++;
			}
		}
		return n;
	}

	private static int countSigner(List<ChannelReaction> rs, int signer) {
		int n = 0;
		for (ChannelReaction r : rs) {
			if (java.util.Arrays.equals(r.getSignerEd25519PubKey(),
					signer(signer))) {
				n++;
			}
		}
		return n;
	}
}
