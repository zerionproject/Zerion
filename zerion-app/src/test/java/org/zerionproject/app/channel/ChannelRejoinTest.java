package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ApplicationStatus;
import org.zerionproject.app.api.channel.ChannelApplication;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelRejoinResult;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelRejoinTest {

	private static final long DAY = 24L * ChannelTestNode.HOUR;

	private final Random random = new Random(4417);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private final java.util.Map<ChannelTestNode,
			org.zerionproject.core.api.identity.IdentityManager> identities =
			new java.util.HashMap<>();
	private final List<Sent> sent =
			Collections.synchronizedList(new ArrayList<>());
	private final List<Sent> seenByImpostors =
			Collections.synchronizedList(new ArrayList<>());
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;
	private byte[] id;
	@Nullable
	private volatile Filter filter;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void aRenewedOpenLinkIsOfferedThenRestoresAccessAndKeepsWhatWasHeld()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "before the renewal", 0L);
		String first = owner.manager.exportInviteLink(id);
		ChannelTestNode member = member(first);
		member.manager.reactToPost(id, 0L, "+");
		member.manager.refreshChannel(id);
		ChannelState held = member.store.getChannel(id);

		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));

		assertEquals(ChannelRejoinResult.CHECK_LINK,
				member.manager.rejoinChannel(renewed));
		assertSameAccess(held, member.store.getChannel(id));
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member, renewed));

		ChannelState now = owner.store.getChannel(id);
		ChannelState taken = member.store.getChannel(id);
		assertArrayEquals(now.getJoinCapability(), taken.getJoinCapability());
		assertArrayEquals(now.getContentKey(), taken.getContentKey());
		assertEquals(now.getCurrentOnion(), taken.getCurrentOnion());
		assertEquals(1, member.manager.getAllReactions(id).size());
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("before the renewal", "after the renewal"),
				bodies(member));

		int mark = sent.size();
		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(renewed));
		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(parse(member, first)));
		for (Sent s : since(mark)) {
			assertEquals("only the onion the newest manifest names is asked",
					now.getCurrentOnion(), s.onion);
		}
		assertEquals(1, member.manager.getAllReactions(id).size());
	}

	@Test
	public void theOwnersOwnLinkIsTakenAsHeldAndChangesNothing()
			throws Exception {
		recordedOwner();
		openChannel();
		owner.manager.publishPost(id, "a post", 0L);
		ownerLinkChangesNothing(owner.manager.exportInviteLink(id));
	}

	@Test
	public void theOwnersRenewedApprovalLinkIsTakenAsHeldAndChangesNothing()
			throws Exception {
		recordedOwner();
		approvalChannel();
		owner.manager.publishPost(id, "a post", 0L);
		owner.manager.rotateJoinCapability(id);
		ownerLinkChangesNothing(owner.manager.exportInviteLink(id));
	}

	@Test
	public void aForeignKeyAndOnionForTheOwnersChannelChangeNothing()
			throws Exception {
		recordedOwner();
		openChannel();
		owner.manager.publishPost(id, "a post", 0L);
		byte[] foreignKey = new byte[32];
		random.nextBytes(foreignKey);
		StringBuilder foreignOnion = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
		for (int i = 0; i < 56; i++) {
			foreignOnion.append(alphabet.charAt(random.nextInt(32)));
		}
		String foreign = new ChannelCodec(crypto).formatInviteLink(id,
				foreignKey, null, true, null, foreignOnion.toString(), false);
		ownerLinkChangesNothing(foreign);
		assertTrue(seenBy(foreignOnion.toString()).isEmpty());
	}

	private void recordedOwner() {
		owner = new ChannelTestNode(crypto, clock, recording());
		nodes.add(owner);
	}

	private void ownerLinkChangesNothing(String link) throws Exception {
		ChannelState before = owner.store.getChannel(id);
		ChannelInviteLink l = parse(owner, link);
		int mark = sent.size();
		assertEquals(ChannelRejoinResult.UNCHANGED,
				owner.manager.rejoinChannel(l));
		assertEquals(ChannelRejoinResult.UNCHANGED,
				owner.manager.rejoinChannelThroughLink(l));
		assertTrue("the owner sent something for a link of its own channel",
				since(mark).isEmpty());
		ChannelLinkCandidates candidates = (ChannelLinkCandidates)
				owner.call("linkCandidates", new Class<?>[0]);
		assertTrue("the owner kept a link candidate",
				candidates.list(id).isEmpty());
		ChannelState after = owner.store.getChannel(id);
		assertSameAccess(before, after);
		assertTrue(after.weArePublisher());
		assertEquals(before.getManifestSeq(), after.getManifestSeq());
	}

	@Test
	public void aForgedOnionSeesNothingFirstAndThenOnlyWhatANewMemberSends()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "before the renewal", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		ChannelState held = member.store.getChannel(id);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String silent = impostor(real, false);
		String relay = impostor(real, true);
		ChannelInviteLink viaSilent = parse(member,
				renewed.replace(real, silent));

		assertEquals(ChannelRejoinResult.CHECK_LINK,
				member.manager.rejoinChannel(viaSilent));
		assertTrue("the link's onion was asked before the user tried it",
				seenBy(silent).isEmpty());
		assertEquals(ChannelRejoinResult.TRYING,
				tryLink(member, viaSilent));
		assertSameAccess(held, member.store.getChannel(id));
		List<Sent> fromMember = seenBy(silent);
		assertEquals(1, fromMember.size());

		ChannelTestNode newcomer = node();
		newcomer.manager.joinChannel(viaSilent);
		try {
			newcomer.manager.bootstrapChannel(id);
		} catch (DbException expected) {
		}
		List<Sent> fromNewcomer = seenBy(silent);
		assertEquals(2, fromNewcomer.size());
		assertSameRequest(fromNewcomer.get(1).request,
				fromMember.get(0).request);

		assertEquals(ChannelRejoinResult.TRYING, tryLink(member,
				parse(member, renewed.replace(real, relay))));
		assertEquals("the member keeps only an address the channel signs",
				real, member.store.getChannel(id).getCurrentOnion());
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("before the renewal", "after the renewal"),
				bodies(member));
	}

	@Test
	public void aBogusCapabilityIsRefusedAndTheWorkingOneStays()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "first", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		ChannelState held = member.store.getChannel(id);

		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(withCapability(parse(member,
						owner.manager.exportInviteLink(id)),
						bogusCapability())));
		assertSameAccess(held, member.store.getChannel(id));
		owner.manager.publishPost(id, "second", 0L);
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("first", "second"), bodies(member));

		held = member.store.getChannel(id);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "third", 0L);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));
		ChannelInviteLink bogus = withCapability(renewed, bogusCapability());
		assertEquals(ChannelRejoinResult.CHECK_LINK,
				member.manager.rejoinChannel(bogus));
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member, bogus));
		assertSameAccess(held, member.store.getChannel(id));

		assertEquals(ChannelRejoinResult.TRYING, tryLink(member, renewed));
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("first", "second", "third"),
				bodies(member));
	}

	@Test
	public void aLinkNamingAnotherPublisherKeyIsRefusedWithoutAsking()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "before the renewal", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		ChannelState held = member.store.getChannel(id);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));
		HybridSignaturePublicKey other = (HybridSignaturePublicKey) crypto
				.generateHybridSignatureKeyPair().getPublic();
		ChannelInviteLink forged = new ChannelInviteLink(id,
				other.getEd25519PublicKey(), null, false,
				renewed.getJoinCapability(), renewed.getOnionAddress(),
				false);
		ChannelInviteLink forgedMlDsa = new ChannelInviteLink(id,
				renewed.getPublisherEd25519PubKey(),
				other.getMlDsaPublicKey(), false,
				renewed.getJoinCapability(), renewed.getOnionAddress(),
				false);
		int mark = sent.size();

		for (ChannelInviteLink link : Arrays.asList(forged, forgedMlDsa)) {
			assertEquals(ChannelRejoinResult.NOT_RESTORED,
					member.manager.rejoinChannel(link));
			assertEquals(ChannelRejoinResult.NOT_RESTORED,
					member.manager.rejoinChannelThroughLink(link));
			assertSameAccess(held, member.store.getChannel(id));
		}
		assertEquals("a link naming another key reached the network",
				mark, sent.size());

		assertEquals(ChannelRejoinResult.TRYING, tryLink(member, renewed));
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("before the renewal", "after the renewal"),
				bodies(member));
	}

	@Test
	public void anApprovalLinkAppliesAgainThroughTheLinkAndKeepsWhatWasHeld()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.publishPost(id, "before the renewal", 0L);
		member.manager.refreshChannel(id);
		assertEquals(Collections.singletonList("before the renewal"),
				bodies(member));

		assertEquals("a member with access is not asked to apply again",
				ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(parse(member,
						owner.manager.exportInviteLink(id))));
		assertEquals(ApplicationStatus.APPROVED,
				member.manager.getMyApplicationStatus(id));
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());

		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));
		assertNull(renewed.getJoinCapability());
		assertTrue(renewed.requiresApproval());
		ChannelState held = member.store.getChannel(id);

		assertEquals("a dark onion offers to apply again with its reason",
				ChannelRejoinResult.APPLY_AGAIN_UNREACHABLE,
				member.manager.rejoinChannel(renewed));
		assertSameAccess(held, member.store.getChannel(id));
		assertEquals(Collections.singletonList("before the renewal"),
				bodies(member));

		member.manager.applyToJoin(renewed, "Member");
		assertSameAccess(held, member.store.getChannel(id));
		assertEquals("a renewal revokes the earlier approval", 1,
				owner.manager.listPendingApplications(id).size());
		approveAndRefresh(member);
		member.manager.refreshChannel(id);

		ChannelState taken = member.store.getChannel(id);
		ChannelState now = owner.store.getChannel(id);
		assertArrayEquals(now.getJoinCapability(), taken.getJoinCapability());
		assertArrayEquals(now.getContentKey(), taken.getContentKey());
		assertEquals(now.getCurrentOnion(), taken.getCurrentOnion());
		assertEquals(Arrays.asList("before the renewal", "after the renewal"),
				bodies(member));
	}

	@Test
	public void aRetiringOnionInAnApprovalLinkMovesNothing()
			throws Exception {
		ChannelTestNode member = approvedMember();
		String retiring = owner.store.getChannel(id).getCurrentOnion();
		rotateOnion();
		member.manager.refreshChannel(id);
		String current = owner.store.getChannel(id).getCurrentOnion();
		assertEquals(current, member.store.getChannel(id).getCurrentOnion());
		ChannelState held = member.store.getChannel(id);
		filter = (onion, type, request) -> retiring.equals(onion)
				? new byte[0] : null;
		ChannelInviteLink old = parse(member,
				owner.manager.exportInviteLink(id).replace(current, retiring));

		int mark = sent.size();

		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(old));

		assertSameAccess(held, member.store.getChannel(id));
		assertTrue("the retiring onion of the link was asked",
				sentTo(retiring, mark).isEmpty());
	}

	@Test
	public void anApplicantWithoutCapabilityIsNotMoved() throws Exception {
		approvalChannel();
		String first = owner.manager.exportInviteLink(id);
		String held = owner.store.getChannel(id).getCurrentOnion();
		ChannelTestNode pending = applicant(first);
		pending.manager.applyToJoin(id, "Pending");
		ChannelTestNode waiting = applicant(first);
		rotateOnion();
		String current = owner.store.getChannel(id).getCurrentOnion();
		ChannelInviteLink link = parse(pending,
				owner.manager.exportInviteLink(id));
		int mark = sent.size();

		assertEquals(ChannelRejoinResult.UNCHANGED,
				pending.manager.rejoinChannel(link));
		assertEquals(held, pending.store.getChannel(id).getCurrentOnion());
		assertEquals(ApplicationStatus.PENDING,
				pending.manager.getMyApplicationStatus(id));

		assertEquals(ChannelRejoinResult.APPLY_AGAIN,
				waiting.manager.rejoinChannel(link));
		waiting.manager.applyToJoin(link, "Waiting");
		assertEquals(held, waiting.store.getChannel(id).getCurrentOnion());
		assertEquals(2, owner.manager.listPendingApplications(id).size());
		for (Sent s : since(mark)) {
			assertNotEquals("an applicant whose onion answers asked the link's",
					current, s.onion);
		}
	}

	@Test
	public void anEmptyReplyWhileAccessWorksMovesNothing() throws Exception {
		ChannelTestNode member = approvedMember();
		String retiring = owner.store.getChannel(id).getCurrentOnion();
		rotateOnion();
		member.manager.refreshChannel(id);
		String current = owner.store.getChannel(id).getCurrentOnion();
		ChannelState held = member.store.getChannel(id);
		boolean[] once = {true};
		filter = (onion, type, request) -> {
			if (!ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)) {
				return null;
			}
			if (retiring.equals(onion)) return new byte[0];
			if (current.equals(onion) && once[0]) {
				once[0] = false;
				return new byte[0];
			}
			return null;
		};
		ChannelInviteLink old = parse(member,
				owner.manager.exportInviteLink(id).replace(current, retiring));

		int mark = sent.size();

		member.manager.rejoinChannel(old);

		assertSameAccess(held, member.store.getChannel(id));
		assertTrue(sentTo(retiring, mark).isEmpty());
		filter = null;
		owner.manager.publishPost(id, "still reachable", 0L);
		member.manager.refreshChannel(id);
		assertEquals(Collections.singletonList("still reachable"),
				bodies(member));
	}

	@Test
	public void aConcurrentManifestChangeIsNotRolledBack() throws Exception {
		ChannelTestNode member = approvedMember();
		rotateOnion();
		String middle = owner.store.getChannel(id).getCurrentOnion();
		rotateOnion();
		String newest = owner.store.getChannel(id).getCurrentOnion();
		filter = (onion, type, request) -> {
			if (!middle.equals(onion) || !ChannelConstants
					.WIRE_TYPE_PULL_REQUEST.equals(type)) {
				return null;
			}
			filter = null;
			try {
				member.manager.refreshChannel(id);
			} catch (DbException ignored) {
			}
			return new byte[0];
		};
		ChannelInviteLink link = parse(member, owner.manager
				.exportInviteLink(id).replace(newest, middle));

		int mark = sent.size();

		member.manager.rejoinChannel(link);

		assertEquals(newest, member.store.getChannel(id).getCurrentOnion());
		assertTrue(sentTo(middle, mark).isEmpty());
	}

	@Test
	public void aHeldChannelsLinkReachesNoOtherOnionBeforeTheUserTriesIt()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "post", 0L);
		String link = owner.manager.exportInviteLink(id);
		ChannelTestNode member = member(link);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String relay = impostor(real, true);
		ChannelInviteLink open = parse(member, link.replace(real, relay));
		ChannelInviteLink approval = new ChannelInviteLink(id,
				open.getPublisherEd25519PubKey(), null, false, null, relay,
				true);
		ChannelInviteLink plain = new ChannelInviteLink(id,
				open.getPublisherEd25519PubKey(), null, true, null, relay,
				false);
		int mark = sent.size();
		for (ChannelInviteLink l : Arrays.asList(open, approval, plain)) {
			assertTrue(member.manager.getChannel(l.getChannelId()) != null);
		}
		assertEquals("opening a link asked something before any choice",
				mark, sent.size());

		for (ChannelInviteLink l : Arrays.asList(open, approval, plain)) {
			member.manager.rejoinChannel(l);
		}

		assertTrue("the link's onion was asked before the user tried it",
				seenByImpostors.isEmpty());
		for (Sent s : since(mark)) {
			assertEquals(real, s.onion);
		}
	}

	@Test
	public void theAccessCheckKeepsThePostsItFetches() throws Exception {
		openChannel();
		owner.manager.publishPost(id, "first", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.publishPost(id, "second", 0L);
		owner.manager.publishPost(id, "third", 0L);
		int mark = sent.size();

		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(parse(member,
						owner.manager.exportInviteLink(id))));

		assertEquals(Arrays.asList("first", "second", "third"),
				bodies(member));
		int pulls = 0;
		for (Sent s : since(mark)) {
			if (ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(s.type)) {
				pulls++;
			}
		}
		assertEquals("the posts were fetched more than once", 1, pulls);
	}

	@Test
	public void aFallbackOnionWhoseManifestNamesItGivesItsPosts()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "first", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		ChannelState held = member.store.getChannel(id);
		String real = held.getCurrentOnion();
		String dark = new String(new char[56]).replace('\0', 'd');
		member.store.putChannel(withOnion(held, dark));
		member.store.onions().remember(id, real);
		owner.manager.publishPost(id, "second", 0L);

		for (int i = 0; i < 4 && bodies(member).size() < 2; i++) {
			try {
				member.manager.refreshChannel(id);
			} catch (DbException expected) {
			}
			clock.advance(ChannelConstants.ONION_FALLBACK_AFTER_MS + 1L);
		}

		assertEquals(Arrays.asList("first", "second"), bodies(member));
	}

	@Test
	public void aReturningMemberLooksLikeANewcomerToASilentLinkOnion()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "before the renewal", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String forNewcomer = impostor(real, false);
		String forMember = impostor(real, false);
		ChannelTestNode newcomer = node();

		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		ChannelInviteLink link = parse(member,
				renewed.replace(real, forMember));
		assertEquals(ChannelRejoinResult.CHECK_LINK,
				member.manager.rejoinChannel(link));
		assertTrue(seenBy(forMember).isEmpty());
		ChannelRejoinResult tried = tryLink(member, link);

		sameTriggers(newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertTrue(seenBy(forMember).size() > 10);
		assertEquals(ChannelRejoinResult.TRYING, tried);
	}

	@Test
	public void aReturningMemberLooksLikeANewcomerToARelayingLinkOnion()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "before the renewal", 0L);
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String forNewcomer = impostor(real, true);
		String forMember = impostor(real, true);
		ChannelTestNode newcomer = node();

		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member,
				parse(member, renewed.replace(real, forMember))));

		sameTriggers(newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertEquals(1, seenBy(forMember).size());
		assertEquals(real, member.store.getChannel(id).getCurrentOnion());
		assertEquals(Arrays.asList("before the renewal", "after the renewal"),
				bodies(member));
	}

	@Test
	public void aReturningApplicantLooksLikeANewcomerToTheLinkOnion()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String forNewcomer = impostor(real, false);
		String forMember = impostor(real, false);
		ChannelTestNode newcomer = node();

		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.applyToJoin(id, "Newcomer"));
		quietly(() -> member.manager.applyToJoin(parse(member,
				renewed.replace(real, forMember)), "Member"));

		sameTriggers(newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertTrue(seenBy(forMember).size() > 10);
	}

	@Test
	public void aPendingApplicationThroughALinkFallsBackToTheHeldOnion()
			throws Exception {
		ChannelTestNode member = approvedMember();
		String held = owner.store.getChannel(id).getCurrentOnion();
		rotateOnion();
		member.manager.refreshChannel(id);
		String current = owner.store.getChannel(id).getCurrentOnion();
		filter = (onion, type, request) -> {
			if (!current.equals(onion)) return null;
			throw new IOException("dark");
		};
		ChannelInviteLink link = parse(member, owner.manager
				.exportInviteLink(id).replace(current, held));

		member.manager.applyToJoin(link, "Member");
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
		clock.advance(31L * DAY);
		owner.manager.maintainOnions();
		filter = null;
		clock.advance(ChannelTestNode.HOUR);
		quietly(() -> member.manager.refreshChannel(id));

		assertEquals(ApplicationStatus.APPROVED,
				member.manager.getMyApplicationStatus(id));
	}

	@Test
	public void aDarkOnionWithACapabilityIsNotOfferedToApplyAgain()
			throws Exception {
		ChannelTestNode member = approvedMember();
		String current = owner.store.getChannel(id).getCurrentOnion();
		ChannelInviteLink link = parse(member,
				owner.manager.exportInviteLink(id));
		filter = (onion, type, request) -> {
			if (!current.equals(onion)) return null;
			throw new IOException("dark");
		};

		assertEquals(ChannelRejoinResult.TRY_LATER,
				member.manager.rejoinChannel(link));
		filter = null;
		assertEquals(ApplicationStatus.APPROVED,
				member.manager.getMyApplicationStatus(id));
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
	}

	@Test
	public void aCapabilityWithoutAManifestIsCheckedBeforeAnyOffer()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "post", 0L);
		String real = owner.store.getChannel(id).getCurrentOnion();
		filter = (onion, type, request) -> {
			if (!real.equals(onion)) return null;
			throw new IOException("dark");
		};
		ChannelTestNode member = applicant(owner.manager.exportInviteLink(id));
		quietly(() -> member.manager.bootstrapChannel(id));
		assertEquals(-1L, member.store.getChannel(id).getManifestSeq());
		filter = null;
		ChannelInviteLink link = parse(member,
				owner.manager.exportInviteLink(id));
		ChannelInviteLink approval = new ChannelInviteLink(id,
				link.getPublisherEd25519PubKey(), null, false, null, real,
				true);

		assertEquals(ChannelRejoinResult.UNCHANGED,
				member.manager.rejoinChannel(approval));
		assertEquals(Collections.singletonList("post"), bodies(member));
	}

	@Test
	public void aMemberThatAppliesAgainLooksLikeANewApplicantToTheLinkOnion()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the renewal", 0L);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String forNewcomer = rewritingImpostor(real);
		String forMember = rewritingImpostor(real);
		ChannelTestNode newcomer = node();
		ChannelInviteLink link = parse(member,
				renewed.replace(real, forMember));

		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.applyToJoin(id, "Newcomer"));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		assertEquals(ChannelRejoinResult.APPLY_AGAIN_UNREACHABLE,
				member.manager.rejoinChannel(link));
		quietly(() -> member.manager.applyToJoin(link, "Member"));
		quietly(() -> member.manager.bootstrapChannel(id));

		sameTriggers(newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertEquals(1, seenBy(forMember).size());
		for (Sent s : seenByImpostors) {
			assertEquals("the link's onion saw more than a question",
					ChannelConstants.WIRE_TYPE_CONFIRM_ONION, s.type);
		}
		assertEquals(2, owner.manager.listPendingApplications(id).size());
		approveAndRefresh(member);
		member.manager.refreshChannel(id);
		ChannelState now = owner.store.getChannel(id);
		ChannelState taken = member.store.getChannel(id);
		assertArrayEquals(now.getJoinCapability(), taken.getJoinCapability());
		assertEquals(now.getCurrentOnion(), taken.getCurrentOnion());
		assertEquals(Collections.singletonList("after the renewal"),
				bodies(member));
	}

	@Test
	public void aRenewalRevokesAnApprovalSoApplyingAgainWaits()
			throws Exception {
		ChannelTestNode member = approvedMember();
		byte[] held = member.store.getChannel(id).getJoinCapability();
		owner.manager.rotateJoinCapability(id);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));

		member.manager.applyToJoin(renewed, "Member");

		ChannelApplication app = owner.manager.listAllApplications(id).get(0);
		assertEquals(ChannelApplication.Status.PENDING, app.getStatus());
		assertNull("a capability was handed out", app.getEnvelope());
		assertNull(app.getKemCiphertext());
		clock.advance(ChannelTestNode.HOUR);
		quietly(() -> member.manager.refreshChannel(id));
		assertArrayEquals(held, member.store.getChannel(id)
				.getJoinCapability());
		assertEquals(ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
	}

	@Test
	public void anApprovalWithoutItsCapabilityMarkIsNotRenewedAtOnce()
			throws Exception {
		ChannelTestNode member = approvedMember();
		ChannelApplication app = owner.manager.listAllApplications(id).get(0);
		owner.applicationStore.putApplication(id, new ChannelApplication(
				app.getDisplayName(), app.getApplicantEd25519(),
				app.getApplicantMlDsa(), app.getApplicantEphemeralAgreementPub(),
				app.getAppliedAtHourMs(), ChannelApplication.Status.APPROVED,
				app.getKemCiphertext(), app.getEnvelope()));
		assertEquals("an approval from before the upgrade is not listed", 1,
				owner.manager.listAllApplications(id).size());
		clock.advance(ChannelTestNode.HOUR);

		member.manager.applyToJoin(id, "Member");

		ChannelApplication again = owner.manager.listAllApplications(id)
				.get(0);
		assertEquals(ChannelApplication.Status.PENDING, again.getStatus());
		assertNull("a capability was wrapped", again.getEnvelope());
		assertNull(again.getKemCiphertext());
	}

	@Test
	public void aReplayedOldReplyLeavesAWaitingApplicationAlone()
			throws Exception {
		ChannelTestNode member = approvedMember();
		byte[] old = member.store.getChannel(id).getJoinCapability();
		byte[] oldReply = owner.handle(id, (byte[]) member.call("pullRequest",
				new Class<?>[] {byte[].class, byte[].class, long.class,
						boolean.class}, id, old, -1L, false));
		owner.manager.rotateJoinCapability(id);
		member.manager.applyToJoin(parse(member,
				owner.manager.exportInviteLink(id)), "Member");
		assertEquals(ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
		int[] asked = new int[1];
		String replaying = network.bindServer(id, "key-replaying",
				request -> {
					asked[0]++;
					return oldReply;
				}).getOnionAddress();
		ChannelInviteLink link = new ChannelInviteLink(id,
				member.store.getChannel(id).getPublisherEd25519PubKey(), null,
				false, old, replaying, false);

		ChannelRejoinResult tried = tryLink(member, link);

		assertEquals("a replayed reply approved the application",
				ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
		assertEquals(ChannelRejoinResult.TRYING, tried);
		assertEquals(1, asked[0]);
		quietly(() -> member.manager.refreshChannelOnOpen(id));
		assertEquals("the candidate did not end", 1, asked[0]);
	}

	@Test
	public void anApprovalLostToASecondRenewalCanBeAskedForAgain()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.rotateJoinCapability(id);
		member.manager.applyToJoin(parse(member,
				owner.manager.exportInviteLink(id)), "Member");
		owner.manager.approveApplication(id, Arrays.copyOfRange(
				member.manager.getMyChannelPublicKey(id), 0, 32));
		owner.manager.rotateJoinCapability(id);
		assertTrue("an approval a renewal revoked is listed",
				owner.manager.listAllApplications(id).isEmpty());
		ChannelInviteLink third = parse(member,
				owner.manager.exportInviteLink(id));
		assertEquals(ChannelRejoinResult.APPLY_AGAIN_UNREACHABLE,
				member.manager.rejoinChannel(third));
		int asked = sent.size();

		member.manager.applyToJoin(third, "Member");

		assertTrue("applying again sent nothing", sent.size() > asked);
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		owner.manager.approveApplication(id, Arrays.copyOfRange(
				member.manager.getMyChannelPublicKey(id), 0, 32));
		clock.advance(ChannelTestNode.HOUR);
		member.manager.refreshChannel(id);
		member.manager.refreshChannel(id);
		assertArrayEquals(owner.store.getChannel(id).getJoinCapability(),
				member.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aReturningMemberOverlapsItsRequestsAsANewcomerDoes()
			throws Exception {
		openChannel();
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		Stall forNewcomer = new Stall();
		Stall forMember = new Stall();
		String newcomerOnion = forNewcomer.bind("key-stall-newcomer");
		String memberOnion = forMember.bind("key-stall-member");
		ChannelTestNode newcomer = node();
		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, newcomerOnion)));
		ChannelInviteLink link = parse(member,
				renewed.replace(real, memberOnion));
		assertEquals(ChannelRejoinResult.CHECK_LINK,
				member.manager.rejoinChannel(link));
		assertEquals(ChannelRejoinResult.TRYING,
				member.manager.rejoinChannelThroughLink(link));
		assertEquals(0, forMember.count);

		together(() -> newcomer.manager.bootstrapChannel(id),
				() -> newcomer.manager.refreshChannelOnOpen(id));
		together(() -> member.manager.bootstrapChannel(id),
				() -> member.manager.refreshChannelOnOpen(id));

		assertEquals(2, forNewcomer.count);
		assertEquals(forNewcomer.count, forMember.count);
		assertEquals(2, forNewcomer.most);
		assertEquals("the link's onion saw requests in another overlap",
				forNewcomer.most, forMember.most);
	}

	@Test
	public void anOpenFeedTicksALinkAsANewcomersFeedWhileTheHeldChannelRests()
			throws Exception {
		openChannel();
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		String held = member.store.getChannel(id).getCurrentOnion();
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		clock.advance(91L * DAY);
		assertTrue(member.manager.getSyncStatus(id).isDormant());
		String forNewcomer = impostor(real, false);
		String forMember = impostor(real, false);
		ChannelTestNode newcomer = node();
		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member,
				parse(member, renewed.replace(real, forMember))));
		openFeeds(newcomer, member);
		int mark = sent.size();

		tickFeeds(6, newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertEquals(8, seenBy(forMember).size());
		assertTrue("the resting channel was pulled on a tick",
				sentTo(held, mark).isEmpty());
	}

	@Test
	public void anOpenFeedRestsALinkAsANewcomersFeedWhileTheHeldChannelWorks()
			throws Exception {
		openChannel();
		String text = owner.manager.exportInviteLink(id);
		ChannelTestNode member = member(text);
		String real = owner.store.getChannel(id).getCurrentOnion();
		String forNewcomer = impostor(real, false);
		String forMember = impostor(real, false);
		ChannelTestNode newcomer = node();
		newcomer.manager.joinChannel(parse(newcomer,
				text.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		filter = (onion, type, request) -> {
			if (real.equals(onion)) throw new IOException();
			return null;
		};
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member,
				parse(member, text.replace(real, forMember))));
		filter = null;
		for (long day : new long[] {30L, 30L, 31L}) {
			clock.advance(day * DAY);
			for (ChannelTestNode n : Arrays.asList(newcomer, member)) {
				n.refreshAndReschedule();
			}
		}
		assertTrue(newcomer.manager.getSyncStatus(id).isDormant());
		assertTrue(!member.manager.getSyncStatus(id).isDormant());
		openFeeds(newcomer, member);
		int mark = sent.size();

		tickFeeds(6, newcomer, member);

		assertSameSequence(forNewcomer, forMember);
		assertTrue("the working channel was not pulled on its ticks",
				sentTo(real, mark).size() >= 6);
	}

	@Test
	public void anApprovalThatNeverVerifiesIsForgottenAfterADayAndNotBefore()
			throws Exception {
		ChannelTestNode member = unverifiableApproval();
		ChannelState held = member.store.getChannel(id);

		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR - 60_000L);
		assertEquals("an approval was forgotten before a day had passed",
				ApplicationStatus.PENDING, refreshed(member));
		clock.advance(60_000L);
		assertEquals("an approval that never verifies was kept",
				ApplicationStatus.NOT_APPLIED, refreshed(member));

		assertSameAccess(held, member.store.getChannel(id));
		int asked = sent.size();
		member.manager.applyToJoin(id, "Member");
		assertTrue("applying again sent nothing", sent.size() > asked);
	}

	@Test
	public void aRestartInTheMiddleKeepsTheRunOfEmptyReplies()
			throws Exception {
		ChannelTestNode member = unverifiableApproval();
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR);

		ChannelTestNode again = restarted(member);

		assertEquals(ApplicationStatus.PENDING, refreshed(again));
		clock.advance(12L * ChannelTestNode.HOUR);
		assertEquals("a restart started the run again",
				ApplicationStatus.NOT_APPLIED, refreshed(again));
	}

	@Test
	public void aClockSetBackStartsTheRunAgainOnlyOnce() throws Exception {
		ChannelTestNode member = unverifiableApproval();
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		byte[] eph = (byte[]) ownApplication(member, "ephemeralAgreementPub");
		long start = (Long) ownApplication(member, "emptySinceMs");
		Class<?>[] types = {byte[].class, byte[].class, long.class};

		assertEquals(false, member.call("emptyRunOver", types, id, eph,
				start - 10L * DAY));
		assertEquals(false, member.call("emptyRunOver", types, id, eph,
				start - 10L * DAY + 22L * ChannelTestNode.HOUR));
		assertEquals(false, member.call("emptyRunOver", types, id, eph,
				start - 20L * DAY));
		assertEquals("the clock moved the run back a second time", false,
				member.call("emptyRunOver", types, id, eph,
						start - 20L * DAY + 24L * ChannelTestNode.HOUR));
		assertEquals(true, member.call("emptyRunOver", types, id, eph,
				start - 10L * DAY + 24L * ChannelTestNode.HOUR));
		assertEquals(start - 10L * DAY,
				(long) (Long) ownApplication(member, "emptySinceMs"));
	}

	@Test
	public void anOwnerThatShedsForMostOfADayAndThenAnswersStillVerifies()
			throws Exception {
		ChannelTestNode member = approvedMember();
		byte[] current = owner.store.getChannel(id).getJoinCapability();
		member.store.putChannel((ChannelState) member.call("withCapability",
				new Class<?>[] {ChannelState.class, byte[].class},
				member.store.getChannel(id), bogusCapability()));
		member.manager.applyToJoin(id, "Member");
		owner.manager.publishPost(id, "pinned", 0L);
		owner.manager.pinPost(id,
				owner.store.getPosts(id).get(0).getSeqNum());
		String onion = owner.store.getChannel(id).getCurrentOnion();
		filter = (o, type, request) -> onion.equals(o)
				&& ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)
				? new byte[0] : null;
		clock.advance(ChannelTestNode.HOUR);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR - 60_000L);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		filter = null;
		clock.advance(31_000L);

		assertEquals(ApplicationStatus.APPROVED, refreshed(member));
		assertArrayEquals(current,
				member.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aCandidateApprovalThatNeverVerifiesIsForgottenAfterADay()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.rotateJoinCapability(id);
		member.manager.applyToJoin(parse(member,
				owner.manager.exportInviteLink(id)), "Member");
		approveWithCapability(bogusCapability());
		clock.advance(ChannelTestNode.HOUR);

		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(12L * ChannelTestNode.HOUR - 60_000L);
		assertEquals(ApplicationStatus.PENDING, refreshed(member));
		clock.advance(60_000L);
		assertEquals("a candidate approval that never verifies was kept",
				ApplicationStatus.NOT_APPLIED, refreshed(member));
	}

	@Test
	public void aVerifyingPullTheOwnerShedsLeavesTheApprovalWaiting()
			throws Exception {
		ChannelTestNode member = approvedMember();
		byte[] current = owner.store.getChannel(id).getJoinCapability();
		member.store.putChannel((ChannelState) member.call("withCapability",
				new Class<?>[] {ChannelState.class, byte[].class},
				member.store.getChannel(id), bogusCapability()));
		member.manager.applyToJoin(id, "Member");
		owner.manager.publishPost(id, "pinned", 0L);
		owner.manager.pinPost(id,
				owner.store.getPosts(id).get(0).getSeqNum());
		String onion = owner.store.getChannel(id).getCurrentOnion();
		int[] pulls = shedFirstPullTo(onion);
		clock.advance(ChannelTestNode.HOUR);

		quietly(() -> member.manager.refreshChannel(id));

		assertTrue(pulls[0] >= 1);
		assertEquals("a reply the owner shed dropped the approval",
				ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
		clock.advance(31_000L);
		quietly(() -> member.manager.refreshChannel(id));
		assertArrayEquals(current,
				member.store.getChannel(id).getJoinCapability());
		assertEquals(ApplicationStatus.APPROVED,
				member.manager.getMyApplicationStatus(id));
	}

	@Test
	public void aCandidatePullTheOwnerShedsLeavesTheApprovalWaiting()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.rotateJoinCapability(id);
		ChannelInviteLink renewed = parse(member,
				owner.manager.exportInviteLink(id));
		member.manager.applyToJoin(renewed, "Member");
		owner.manager.approveApplication(id, Arrays.copyOfRange(
				member.manager.getMyChannelPublicKey(id), 0, 32));
		String onion = owner.store.getChannel(id).getCurrentOnion();
		int[] pulls = shedFirstPullTo(onion);
		clock.advance(ChannelTestNode.HOUR);

		quietly(() -> member.manager.refreshChannel(id));

		assertTrue(pulls[0] >= 1);
		assertEquals("a reply the owner shed dropped the approval",
				ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
		clock.advance(31_000L);
		quietly(() -> member.manager.refreshChannel(id));
		assertArrayEquals(owner.store.getChannel(id).getJoinCapability(),
				member.store.getChannel(id).getJoinCapability());
		assertEquals(ApplicationStatus.APPROVED,
				member.manager.getMyApplicationStatus(id));
	}

	@Test
	public void aFifthLinkIsNotTriedAndSaysSo() throws Exception {
		openChannel();
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		for (int i = 0; i < ChannelLinkCandidates.MAX_PER_CHANNEL; i++) {
			assertEquals(ChannelRejoinResult.TRYING,
					member.manager.rejoinChannelThroughLink(parse(member,
							renewed.replace(real, impostor(real, false)))));
		}
		String fifth = impostor(real, false);

		assertEquals(ChannelRejoinResult.TOO_MANY_LINKS,
				member.manager.rejoinChannelThroughLink(parse(member,
						renewed.replace(real, fifth))));

		quietly(() -> member.manager.bootstrapChannel(id));
		assertTrue("a link that was not kept was tried",
				seenBy(fifth).isEmpty());
	}

	@Test
	public void aReachedLinkOnionRestartsTheRestAsForANewcomer()
			throws Exception {
		openChannel();
		ChannelTestNode member = member(owner.manager.exportInviteLink(id));
		owner.manager.rotateJoinCapability(id);
		String renewed = owner.manager.exportInviteLink(id);
		String real = owner.store.getChannel(id).getCurrentOnion();
		boolean[] tombstone = new boolean[1];
		byte[] bogus = owner.pullCodec.encodeTombstone(id,
				ChannelTestNode.HOUR, new byte[64]);
		String forNewcomer = answering(tombstone, bogus);
		String forMember = answering(tombstone, bogus);
		ChannelTestNode newcomer = node();
		newcomer.manager.joinChannel(parse(newcomer,
				renewed.replace(real, forNewcomer)));
		quietly(() -> newcomer.manager.bootstrapChannel(id));
		assertEquals(ChannelRejoinResult.TRYING, tryLink(member, parse(member,
				renewed.replace(real, forMember))));

		clock.advance(10L * DAY);
		tombstone[0] = true;
		for (ChannelTestNode n : Arrays.asList(newcomer, member)) {
			n.refreshAndReschedule();
		}
		tombstone[0] = false;
		assertNotNull("a tombstone that does not verify was applied",
				newcomer.store.getChannel(id));
		assertNotNull("a tombstone that does not verify was applied",
				member.store.getChannel(id));
		for (long day : new long[] {60L, 25L, 4L, 2L}) {
			clock.advance(day * DAY);
			for (ChannelTestNode n : Arrays.asList(newcomer, member)) {
				n.refreshAndReschedule();
			}
		}

		assertSameSequence(forNewcomer, forMember);
		assertEquals(5, seenBy(forMember).size());
	}

	@Test
	public void aSameLinkThatIsRefusedSaysAccessWasNotRestored()
			throws Exception {
		openChannel();
		owner.manager.publishPost(id, "post", 0L);
		String text = owner.manager.exportInviteLink(id);
		ChannelTestNode member = member(text);
		String current = owner.store.getChannel(id).getCurrentOnion();
		filter = (onion, type, request) -> current.equals(onion)
				&& ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)
				? new byte[0] : null;
		ChannelInviteLink link = parse(member, text);

		assertEquals(ChannelRejoinResult.NOT_RESTORED,
				member.manager.rejoinChannel(link));
		assertEquals(ChannelRejoinResult.NOT_RESTORED,
				member.manager.rejoinChannelThroughLink(link));
	}

	@Test
	public void aChannelTheOwnerDeletedIsReportedAsDeleted()
			throws Exception {
		openChannel();
		String text = owner.manager.exportInviteLink(id);
		ChannelTestNode first = member(text);
		ChannelTestNode second = member(text);
		String current = owner.store.getChannel(id).getCurrentOnion();
		owner.manager.deleteChannel(id);
		String elsewhere = new String(new char[56]).replace('\0', 'e');

		assertEquals(ChannelRejoinResult.DELETED,
				first.manager.rejoinChannel(parse(first, text)));
		assertNull(first.store.getChannel(id));
		assertEquals(ChannelRejoinResult.DELETED,
				second.manager.rejoinChannelThroughLink(parse(second,
						text.replace(current, elsewhere))));
		assertNull(second.store.getChannel(id));
	}

	@Test
	public void anApprovalThatDoesNotOpenTheChannelLeavesTheHeldAccess()
			throws Exception {
		ChannelTestNode member = approvedMember();
		owner.manager.publishPost(id, "first", 0L);
		member.manager.refreshChannel(id);
		ChannelState held = member.store.getChannel(id);
		byte[] genuine = owner.handle(id, (byte[]) member.call("pullRequest",
				new Class<?>[] {byte[].class, byte[].class, long.class,
						boolean.class}, id, held.getJoinCapability(), -1L,
				false));
		member.manager.applyToJoin(id, "Member");
		ChannelApplication app = owner.manager.listAllApplications(id).get(0);
		org.zerionproject.core.api.crypto.HybridEncapsulationResult encap =
				crypto.hybridEncapsulate(crypto.getHybridAgreementKeyParser()
						.parsePublicKey(app.getApplicantEphemeralAgreementPub()));
		byte[] envelope = (byte[]) owner.call("wrapApprovalCapability",
				new Class<?>[] {byte[].class, byte[].class, byte[].class}, id,
				encap.getSharedSecret(), bogusCapability());
		owner.applicationStore.putApplication(id, new ChannelApplication(
				app.getDisplayName(), app.getApplicantEd25519(),
				app.getApplicantMlDsa(), app.getApplicantEphemeralAgreementPub(),
				app.getAppliedAtHourMs(), ChannelApplication.Status.APPROVED,
				encap.getCiphertext(), envelope, crypto.hash(
				"org.zerionproject/CHANNEL_APPROVAL_CAPABILITY",
				owner.store.getChannel(id).getJoinCapability())));

		String onion = held.getCurrentOnion();
		int[] pulls = shedFirstPullTo(onion);
		clock.advance(ChannelTestNode.HOUR);
		quietly(() -> member.manager.refreshChannel(id));
		assertTrue(pulls[0] >= 1);
		assertSameAccess(held, member.store.getChannel(id));
		assertEquals("an empty reply was taken as a refusal",
				ApplicationStatus.PENDING,
				member.manager.getMyApplicationStatus(id));
		boolean[] refused = new boolean[1];
		filter = (o, type, request) -> onion.equals(o) && !refused[0]
				&& ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)
				&& (refused[0] = true) ? genuine : null;
		clock.advance(31_000L);
		quietly(() -> member.manager.refreshChannel(id));
		filter = null;

		assertTrue(refused[0]);
		assertSameAccess(held, member.store.getChannel(id));
		assertEquals("an approval that opens nothing was kept",
				ApplicationStatus.NOT_APPLIED,
				member.manager.getMyApplicationStatus(id));
		int asked = sent.size();
		member.manager.applyToJoin(id, "Member");
		assertTrue("applying again sent nothing", sent.size() > asked);
		owner.manager.publishPost(id, "second", 0L);
		member.manager.refreshChannel(id);
		assertEquals(Arrays.asList("first", "second"), bodies(member));
	}

	private interface Action {
		void run() throws Exception;
	}

	private Object ownApplication(ChannelTestNode n, String field)
			throws Exception {
		java.lang.reflect.Field store = ChannelManagerImpl.class
				.getDeclaredField("myApplicationsStore");
		store.setAccessible(true);
		Object app = ((ChannelMyApplicationsStore) store.get(n.manager))
				.get(id);
		java.lang.reflect.Field f = app.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return f.get(app);
	}

	private ChannelRejoinResult tryLink(ChannelTestNode n,
			ChannelInviteLink link) throws Exception {
		ChannelRejoinResult r = n.manager.rejoinChannelThroughLink(link);
		if (r == ChannelRejoinResult.TRYING) {
			quietly(() -> n.manager.bootstrapChannel(id));
		}
		return r;
	}

	private int[] shedFirstPullTo(String onion) {
		int[] pulls = new int[1];
		filter = (o, type, request) -> {
			if (!onion.equals(o)
					|| !ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)) {
				return null;
			}
			return ++pulls[0] == 1 ? new byte[0] : null;
		};
		return pulls;
	}

	private void openFeeds(ChannelTestNode... feeds) throws Exception {
		for (ChannelTestNode n : feeds) {
			quietly(() -> n.manager.refreshChannelOnOpen(id));
		}
	}

	private void tickFeeds(int ticks, ChannelTestNode... feeds)
			throws Exception {
		for (int i = 0; i < ticks; i++) {
			clock.advance(12_000L);
			for (ChannelTestNode n : feeds) quietly(() -> tick(n));
		}
	}

	private void tick(ChannelTestNode n) throws Exception {
		n.manager.refreshChannelTick(id);
	}

	private static void together(Action a, Action b) throws Exception {
		Thread first = new Thread(() -> {
			try {
				quietly(a);
			} catch (Exception ignored) {
			}
		});
		Thread second = new Thread(() -> {
			try {
				quietly(b);
			} catch (Exception ignored) {
			}
		});
		first.start();
		second.start();
		first.join(20_000L);
		second.join(20_000L);
	}

	private final class Stall {
		int active;
		int most;
		int count;

		String bind(String key) throws IOException {
			return network.bindServer(id, key, request -> {
				synchronized (this) {
					active++;
					count++;
					most = Math.max(most, active);
					notifyAll();
					long until = System.currentTimeMillis() + 1_500L;
					while (active < 2 && System.currentTimeMillis() < until) {
						try {
							wait(Math.max(1L,
									until - System.currentTimeMillis()));
						} catch (InterruptedException e) {
							break;
						}
					}
					active--;
				}
				return new byte[0];
			}).getOnionAddress();
		}
	}

	private String answering(boolean[] tombstone, byte[] bogus)
			throws IOException {
		String key = "key-answering-" + random.nextInt();
		String[] self = new String[1];
		self[0] = network.bindServer(id, key, request -> {
			seenByImpostors.add(new Sent(self[0], typeOf(request), request,
					clock.currentTimeMillis()));
			return tombstone[0] ? bogus : new byte[0];
		}).getOnionAddress();
		return self[0];
	}

	private static void quietly(Action a) throws Exception {
		try {
			a.run();
		} catch (DbException expected) {
		}
	}

	private void sameTriggers(ChannelTestNode... both) throws Exception {
		for (ChannelTestNode n : both) {
			quietly(() -> n.manager.refreshChannelOnOpen(id));
		}
		for (int i = 0; i < 3; i++) {
			clock.advance(4_000L);
			for (ChannelTestNode n : both) {
				quietly(() -> n.manager.refreshChannel(id));
			}
		}
		for (int i = 0; i < 12; i++) {
			clock.advance(30L * 60_000L);
			for (ChannelTestNode n : both) n.refreshAndReschedule();
		}
		clock.advance(91L * DAY);
		for (int i = 0; i < 4; i++) {
			clock.advance(30L * 60_000L);
			for (ChannelTestNode n : both) n.refreshAndReschedule();
		}
		for (ChannelTestNode n : both) {
			quietly(() -> n.manager.refreshChannelOnOpen(id));
		}
		for (int i = 0; i < 2; i++) {
			clock.advance(30L * 60_000L);
			for (ChannelTestNode n : both) n.refreshAndReschedule();
		}
	}

	private void assertSameSequence(String a, String b) throws Exception {
		List<Sent> first = seenBy(a);
		List<Sent> second = seenBy(b);
		assertEquals("the link's onion could count the difference",
				first.size(), second.size());
		for (int i = 0; i < first.size(); i++) {
			assertEquals("request " + i + " came at another time",
					first.get(i).at, second.get(i).at);
			assertEquals(first.get(i).type, second.get(i).type);
			assertSameRequest(first.get(i).request, second.get(i).request,
					a, b);
		}
	}

	private void openChannel() throws Exception {
		id = owner.manager.createChannel("name", "about", false)
				.getChannelId();
	}

	private void approvalChannel() throws Exception {
		id = owner.manager.createChannel("name", "about", false, true)
				.getChannelId();
	}

	private ChannelTestNode approvedMember() throws Exception {
		approvalChannel();
		ChannelTestNode n = applicant(owner.manager.exportInviteLink(id));
		n.manager.applyToJoin(id, "Member");
		approveAndRefresh(n);
		return n;
	}

	private void rotateOnion() throws Exception {
		owner.call("rotateOnionLocked", new Class<?>[] {byte[].class}, id);
	}

	private ChannelTestNode node() {
		org.zerionproject.core.api.identity.IdentityManager identity =
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair());
		ChannelTestNode n = new ChannelTestNode(crypto, clock, recording(),
				null, identity);
		nodes.add(n);
		identities.put(n, identity);
		return n;
	}

	private ChannelTestNode restarted(ChannelTestNode n) {
		ChannelTestNode again = new ChannelTestNode(crypto, clock,
				recording(), null, identities.get(n), n.settings, n.root);
		nodes.add(again);
		identities.put(again, identities.get(n));
		return again;
	}

	private void approveWithCapability(byte[] capability) throws Exception {
		ChannelApplication app = owner.manager.listAllApplications(id).get(0);
		org.zerionproject.core.api.crypto.HybridEncapsulationResult encap =
				crypto.hybridEncapsulate(crypto.getHybridAgreementKeyParser()
						.parsePublicKey(app.getApplicantEphemeralAgreementPub()));
		byte[] envelope = (byte[]) owner.call("wrapApprovalCapability",
				new Class<?>[] {byte[].class, byte[].class, byte[].class}, id,
				encap.getSharedSecret(), capability);
		owner.applicationStore.putApplication(id, new ChannelApplication(
				app.getDisplayName(), app.getApplicantEd25519(),
				app.getApplicantMlDsa(), app.getApplicantEphemeralAgreementPub(),
				app.getAppliedAtHourMs(), ChannelApplication.Status.APPROVED,
				encap.getCiphertext(), envelope, crypto.hash(
				"org.zerionproject/CHANNEL_APPROVAL_CAPABILITY",
				owner.store.getChannel(id).getJoinCapability())));
	}

	private ChannelTestNode unverifiableApproval() throws Exception {
		ChannelTestNode member = approvedMember();
		member.manager.applyToJoin(id, "Member");
		approveWithCapability(bogusCapability());
		clock.advance(ChannelTestNode.HOUR);
		return member;
	}

	private ApplicationStatus refreshed(ChannelTestNode n) throws Exception {
		quietly(() -> n.manager.refreshChannel(id));
		return n.manager.getMyApplicationStatus(id);
	}

	private ChannelTestNode member(String link) throws Exception {
		ChannelTestNode n = applicant(link);
		n.manager.refreshChannel(id);
		return n;
	}

	private ChannelTestNode applicant(String link) throws Exception {
		ChannelTestNode n = node();
		n.manager.joinChannel(n.manager.parseInviteLink(link));
		return n;
	}

	private void approveAndRefresh(ChannelTestNode member) throws Exception {
		owner.manager.approveApplication(id, Arrays.copyOfRange(
				member.manager.getMyChannelPublicKey(id), 0, 32));
		clock.advance(ChannelTestNode.HOUR);
		member.manager.refreshChannel(id);
	}

	private static ChannelInviteLink parse(ChannelTestNode n, String link) {
		ChannelInviteLink l = n.manager.parseInviteLink(link);
		if (l == null) throw new AssertionError(link);
		return l;
	}

	private interface Filter {
		@Nullable
		byte[] reply(String onion, String type, byte[] request)
				throws IOException;
	}

	private static final class Sent {
		final String onion;
		final String type;
		final byte[] request;
		final long at;

		Sent(String onion, String type, byte[] request, long at) {
			this.onion = onion;
			this.type = type;
			this.request = request;
			this.at = at;
		}
	}

	private ChannelTransport recording() {
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] channelId,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				return network.bindServer(channelId, onionPrivateKey,
						handler);
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				String type = typeOf(request);
				sent.add(new Sent(onion, type, request,
						clock.currentTimeMillis()));
				Filter f = filter;
				byte[] replaced = f == null ? null
						: f.reply(onion, type, request);
				if (replaced != null) return replaced;
				return network.requestFromOnion(onion, request);
			}

			@Override
			public boolean isReachable(String onion) {
				return network.isReachable(onion);
			}
		};
	}

	private String rewritingImpostor(String real) throws IOException {
		String key = "key-rewriting-" + random.nextInt();
		String[] self = new String[1];
		self[0] = network.bindServer(id, key, request -> {
			seenByImpostors.add(new Sent(self[0], typeOf(request), request,
					clock.currentTimeMillis()));
			try {
				BdfDictionary d = read(request);
				if (ChannelConstants.WIRE_TYPE_CONFIRM_ONION.equals(
						d.getString("type"))) {
					d.put("onion", real);
					return network.requestFromOnion(real, write(d));
				}
				return network.requestFromOnion(real, request);
			} catch (Exception e) {
				return new byte[0];
			}
		}).getOnionAddress();
		return self[0];
	}

	private String impostor(String real, boolean relays) throws IOException {
		String key = "key-impostor-" + relays + "-" + random.nextInt();
		String[] self = new String[1];
		self[0] = network.bindServer(id, key, request -> {
			seenByImpostors.add(new Sent(self[0], typeOf(request), request,
					clock.currentTimeMillis()));
			if (!relays) return new byte[0];
			try {
				return network.requestFromOnion(real, request);
			} catch (IOException e) {
				return new byte[0];
			}
		}).getOnionAddress();
		return self[0];
	}

	private List<Sent> seenBy(String onion) {
		List<Sent> out = new ArrayList<>();
		synchronized (seenByImpostors) {
			for (Sent s : seenByImpostors) {
				if (s.onion.equals(onion)) out.add(s);
			}
		}
		return out;
	}

	private List<Sent> sentTo(String onion, int mark) {
		List<Sent> out = new ArrayList<>();
		for (Sent s : since(mark)) {
			if (s.onion.equals(onion)) out.add(s);
		}
		return out;
	}

	private List<Sent> since(int mark) {
		synchronized (sent) {
			return new ArrayList<>(sent.subList(mark, sent.size()));
		}
	}

	private byte[] bogusCapability() {
		byte[] b = new byte[ChannelConstants.JOIN_CAPABILITY_BYTES];
		random.nextBytes(b);
		return b;
	}

	private static ChannelInviteLink withCapability(ChannelInviteLink l,
			byte[] capability) {
		return new ChannelInviteLink(l.getChannelId(),
				l.getPublisherEd25519PubKey(), l.getPublisherMlDsaPubKey(),
				false, capability, l.getOnionAddress(), false);
	}

	private static void assertSameAccess(ChannelState expected,
			ChannelState actual) {
		assertArrayEquals(expected.getJoinCapability(),
				actual.getJoinCapability());
		assertArrayEquals(expected.getContentKey(), actual.getContentKey());
		assertEquals(expected.getCurrentOnion(), actual.getCurrentOnion());
		assertEquals(expected.getManifestSeq(), actual.getManifestSeq());
	}

	private static void assertSameRequest(byte[] newcomer, byte[] member,
			String newcomerOnion, String memberOnion) throws Exception {
		BdfDictionary a = read(newcomer);
		BdfDictionary b = read(member);
		assertEquals(new TreeSet<>(a.keySet()), new TreeSet<>(b.keySet()));
		for (String k : a.keySet()) {
			Object x = a.get(k);
			Object y = b.get(k);
			if (k.equals("onion")) {
				assertEquals(newcomerOnion, x);
				assertEquals(memberOnion, y);
			} else if (k.equals("nonce") || k.equals("hmacResponse")
					|| k.equals("hmac2") || k.equals("ed") || k.equals("ml")
					|| k.equals("sig") || k.equals("eph")) {
				assertEquals(k, ((byte[]) x).length, ((byte[]) y).length);
			} else if (x instanceof byte[]) {
				assertArrayEquals(k, (byte[]) x, (byte[]) y);
			} else {
				assertEquals(k, x, y);
			}
		}
	}

	private static void assertSameRequest(byte[] newcomer, byte[] member)
			throws Exception {
		BdfDictionary a = read(newcomer);
		BdfDictionary b = read(member);
		assertEquals(new TreeSet<>(a.keySet()), new TreeSet<>(b.keySet()));
		for (String k : a.keySet()) {
			Object x = a.get(k);
			Object y = b.get(k);
			if (k.equals("nonce") || k.equals("hmacResponse")
					|| k.equals("hmac2")) {
				assertEquals(k, ((byte[]) x).length, ((byte[]) y).length);
			} else if (x instanceof byte[]) {
				assertArrayEquals(k, (byte[]) x, (byte[]) y);
			} else {
				assertEquals(k, x, y);
			}
		}
	}

	private static ChannelState withOnion(ChannelState s, String onion) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), onion, s.getManifestSeq(),
				s.weArePublisher(), s.getHighestKnownPostSeq(),
				s.getContentKeyHash(), s.getContentKey(),
				s.getActiveDelegations(), s.getRevokedDelegationSeqs(),
				s.getNextDelegationSeq(), s.getOnionPrivateKey(),
				s.getPinnedPostSeq(), s.requiresApproval(),
				s.getRetiredDelegations());
	}

	private List<String> bodies(ChannelTestNode n) throws Exception {
		List<String> out = new ArrayList<>();
		for (ChannelPost p : n.manager.getRecentPosts(id, 10L)) {
			out.add(p.getBody());
		}
		return out;
	}

	private static String typeOf(byte[] request) {
		try {
			return read(request).getString("type");
		} catch (Exception e) {
			return "?";
		}
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		org.zerionproject.core.api.data.BdfWriter w =
				DaggerChannelCodecTestComponent.create()
						.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}

	private static BdfDictionary read(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}
}
