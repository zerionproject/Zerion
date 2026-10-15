package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelApplicationOnionTest {

	private final Random random = new Random(9173);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private final List<String> seenByImpostor =
			Collections.synchronizedList(new ArrayList<>());
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;
	private byte[] id;
	private String real;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
		id = owner.manager.createChannel("name", "about", false, true)
				.getChannelId();
		owner.manager.publishPost(id, "for members", 0L);
		real = owner.store.getChannel(id).getCurrentOnion();
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void anImpostorOnTheLinkOnionReceivesNoApplication()
			throws Exception {
		String fake = impostor(false);
		ChannelTestNode applicant = joinThrough(linkWith(fake));

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application went to an onion the owner never confirmed");
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN));
		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
		assertNull(new ChannelMyApplicationsStore(applicant.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory())
				.get(id));
	}

	@Test
	public void theApplicationReachesTheOnionTheOwnerConfirms()
			throws Exception {
		String fake = impostor(true);
		ChannelTestNode applicant = joinThrough(linkWith(fake));

		applicant.manager.applyToJoin(id, "Applicant");

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN));
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		assertEquals(real, applicant.store.getChannel(id).getCurrentOnion());

		approveAndRefresh(applicant);

		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant));
		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
	}

	@Test
	public void aCheckForApprovalGoesOnlyToTheOnionTheOwnerConfirms()
			throws Exception {
		String fake = impostor(true);
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		ChannelState held = applicant.store.getChannel(id);
		applicant.store.putChannel(withOnion(held, fake));

		approveAndRefresh(applicant);

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
		assertNotNull("the approval was read from the owner's onion",
				applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant));
	}

	@Test
	public void aCheckForApprovalIsNotSentWhereNoOwnerConfirms()
			throws Exception {
		String fake = impostor(false);
		ChannelTestNode applicant = joinThrough(owner.manager
				.exportInviteLink(id));
		applicant.manager.applyToJoin(id, "Applicant");
		ChannelState held = applicant.store.getChannel(id);
		applicant.store.putChannel(withOnion(held, fake));
		owner.manager.approveApplication(id, applicantKey(applicant));

		clock.advance(ChannelTestNode.HOUR);
		try {
			applicant.manager.refreshChannel(id);
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_CHECK_APPROVAL));
		assertNull(applicant.store.getChannel(id).getJoinCapability());
	}

	@Test
	public void aMemberThatTookNoManifestNamesItselfOnlyToTheOwner()
			throws Exception {
		id = owner.manager.createChannel("open", "about", true)
				.getChannelId();
		real = owner.store.getChannel(id).getCurrentOnion();
		ChannelTestNode member = joinThrough(linkWith(impostor(false)));

		try {
			member.manager.announceMyself(id, "Member");
			fail("a name went to an onion the owner never confirmed");
		} catch (DbException expected) {
		}

		assertEquals(Collections.emptyList(),
				typesSeen(ChannelConstants.WIRE_TYPE_ANNOUNCE));
		assertTrue(owner.manager.getAnnouncedSubscribers(id).isEmpty());
	}

	private void approveAndRefresh(ChannelTestNode applicant)
			throws Exception {
		owner.manager.approveApplication(id, applicantKey(applicant));
		clock.advance(ChannelTestNode.HOUR);
		applicant.manager.refreshChannel(id);
	}

	private byte[] applicantKey(ChannelTestNode applicant) throws Exception {
		return java.util.Arrays.copyOfRange(
				applicant.manager.getMyChannelPublicKey(id), 0, 32);
	}

	private String impostor(boolean asksForTheRealOnion) throws IOException {
		return network.bindServer(id, "key-impostor", request -> {
			try {
				BdfDictionary d = read(request);
				String type = d.getString("type");
				seenByImpostor.add(type);
				if (asksForTheRealOnion && ChannelConstants
						.WIRE_TYPE_CONFIRM_ONION.equals(type)) {
					d.put("onion", real);
					return network.requestFromOnion(real, write(d));
				}
				return network.requestFromOnion(real, request);
			} catch (Exception e) {
				return new byte[0];
			}
		}).getOnionAddress();
	}

	private List<String> typesSeen(String type) {
		List<String> out = new ArrayList<>();
		synchronized (seenByImpostor) {
			for (String t : seenByImpostor) {
				if (t.equals(type)) out.add(t);
			}
		}
		return out;
	}

	private String linkWith(String onion) throws Exception {
		return owner.manager.exportInviteLink(id).replace(real, onion);
	}

	private ChannelTestNode joinThrough(String link) throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network, null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		n.manager.joinChannel(n.manager.parseInviteLink(link));
		return n;
	}

	private List<String> bodies(ChannelTestNode n) throws Exception {
		List<String> out = new ArrayList<>();
		for (org.zerionproject.app.api.channel.ChannelPost p
				: n.manager.getRecentPosts(id, 10L)) {
			out.add(p.getBody());
		}
		return out;
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

	private static BdfDictionary read(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
