package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelCompatibilityBoundariesTest {

	private static final long SHIPPED_BEFORE_3018 = 2L;

	private final Random random = new Random(5521);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;

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
	public void releasesUpTo3017SentVersionTwo() {
		assertEquals(ChannelConstants.PROTOCOL_VERSION_DELTAS,
				SHIPPED_BEFORE_3018);
		assertTrue(ChannelConstants.PROTOCOL_VERSION
				>= ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS);
	}

	@Test
	public void aNewApplicantToAnOwnerOn3017IsToldItFailedAndRecordsNothing()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", false, true)
				.getChannelId();
		owner.manager.publishPost(id, "for members", 0L);
		String onion = owner.store.getChannel(id).getCurrentOnion();
		ownerAnswersAsBefore3018(id);
		ChannelTestNode applicant = subscribe(id);

		try {
			applicant.manager.applyToJoin(id, "Applicant");
			fail("an application was reported sent to an owner that cannot"
					+ " confirm its onion");
		} catch (DbException expected) {
		}

		assertTrue(owner.manager.listPendingApplications(id).isEmpty());
		assertNull(new ChannelMyApplicationsStore(applicant.settings,
				DaggerChannelCodecTestComponent.create().getBdfReaderFactory(),
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory())
				.get(id));
		assertNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(onion, applicant.store.getChannel(id).getCurrentOnion());

		ownerUpdates(id);
		applicant.manager.applyToJoin(id, "Applicant");
		assertEquals(1, owner.manager.listPendingApplications(id).size());
		owner.manager.approveApplication(id, Arrays.copyOfRange(
				applicant.manager.getMyChannelPublicKey(id), 0, 32));
		clock.advance(ChannelTestNode.HOUR);
		applicant.manager.refreshChannel(id);
		assertNotNull(applicant.store.getChannel(id).getJoinCapability());
		assertEquals(Collections.singletonList("for members"),
				bodies(applicant, id));
	}

	@Test
	public void aReaderOn3017NeverGetsAPostThatWasLaterEdited()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "untouched", 0L);
		owner.manager.publishPost(id, "as first written", 0L);
		owner.manager.publishPost(id, editMark(id, 1L, "corrected"), 0L);
		owner.manager.publishPost(id, "after the edit", 0L);

		byte[] reply = pullReply(id, SHIPPED_BEFORE_3018);
		List<ChannelPost> served = owner.pullCodec
				.decodePullResponse(reply, id).newPosts;

		assertEquals(Arrays.asList(0L, 3L), seqs(served));
		for (ChannelPost p : served) {
			assertFalse(p.isErased());
			assertFalse(p.getBody().startsWith("ZRN_EDIT:"));
		}
		for (String text : new String[] {"as first written", "corrected"}) {
			assertFalse(text, ChannelEditErasureTest.contains(reply,
					text.getBytes(StandardCharsets.UTF_8)));
		}

		ChannelTestNode current = join(id);
		List<String> shown = bodies(current, id);
		assertTrue(shown.contains("corrected"));
		assertFalse(shown.contains("as first written"));
	}

	private void ownerAnswersAsBefore3018(byte[] id) throws Exception {
		network.bindServer(id, owner.store.getChannel(id)
				.getOnionPrivateKey(), request -> {
			try {
				if (ChannelConstants.WIRE_TYPE_CONFIRM_ONION.equals(
						read(request).getString("type"))) {
					return (byte[]) owner.call("handlePullRequest",
							new Class<?>[] {byte[].class, byte[].class},
							id, request);
				}
				return owner.handle(id, request);
			} catch (Exception e) {
				return new byte[0];
			}
		});
	}

	private void ownerUpdates(byte[] id) throws Exception {
		network.bindServer(id, owner.store.getChannel(id)
				.getOnionPrivateKey(), request -> {
			try {
				return owner.handle(id, request);
			} catch (Exception e) {
				return new byte[0];
			}
		});
	}

	private ChannelTestNode subscribe(byte[] id) throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network, null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		n.manager.joinChannel(n.manager.parseInviteLink(
				owner.manager.exportInviteLink(id)));
		return n;
	}

	private ChannelTestNode join(byte[] id) throws Exception {
		ChannelTestNode n = subscribe(id);
		n.manager.refreshChannel(id);
		return n;
	}

	private static List<String> bodies(ChannelTestNode n, byte[] id)
			throws Exception {
		List<String> out = new ArrayList<>();
		for (ChannelPost p : n.manager.getRecentPosts(id, 10L)) {
			out.add(p.getBody());
		}
		return out;
	}

	private byte[] pullReply(byte[] id, long version) throws Exception {
		byte[] request = owner.pullCodec.encodePullRequest(id, -1L, null,
				null);
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(request));
		BdfDictionary d = r.readDictionary();
		d.put("v", version);
		return owner.handle(id, write(d));
	}

	private static String editMark(byte[] id, long target, String text) {
		return "ZRN_EDIT:" + ChannelStore.hex(id) + ":" + target + ":" + text;
	}

	private static List<Long> seqs(List<ChannelPost> posts) {
		List<Long> out = new ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getSeqNum());
		return out;
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
