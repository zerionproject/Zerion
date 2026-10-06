package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelApplyAnnounceBoundsTest {

	private static final String SUBSCRIBERS_NS =
			"zerion-channels-subscribers";
	private static final String APPLICATIONS_NS =
			"zerion-channels-applications";
	private static final int AGREEMENT_PUB = 32 + 1184;
	private static final int MAX_SUBSCRIBERS = 4096;

	private final Random random = new Random(193);
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel open;
	private ChannelTestNode.TestChannel gated;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		open = new ChannelTestNode.TestChannel(crypto, random);
		node.seedPublisher(open, true, null, null, false,
				node.posts(open, 1));
		gated = new ChannelTestNode.TestChannel(crypto, random);
		byte[] capability = new byte[32];
		random.nextBytes(capability);
		node.seedPublisher(gated, false, capability,
				node.contentKey.generateContentKey(), true,
				node.posts(gated, 1));
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void aWellFormedApplicationIsTakenIn() throws Exception {
		assertTrue(apply(crypto.generateHybridSignatureKeyPair(), "Ann",
				eph(AGREEMENT_PUB)));
		assertEquals(1, node.applicationStore
				.getApplications(gated.channelId).size());
	}

	@Test
	public void anOversizedApplicationNameIsRefusedAndStoresNothing()
			throws Exception {
		assertFalse("an oversized name is refused",
				apply(crypto.generateHybridSignatureKeyPair(),
						repeat('n', 10_000), eph(AGREEMENT_PUB)));
		assertTrue(node.applicationStore.getApplications(gated.channelId)
				.isEmpty());
		assertEquals(0, node.settings.writes(APPLICATIONS_NS));
	}

	@Test
	public void anOversizedEphemeralKeyIsRefusedAndStoresNothing()
			throws Exception {
		assertFalse("an oversized ephemeral key is refused",
				apply(crypto.generateHybridSignatureKeyPair(), "Ann",
						eph(60_000)));
		assertTrue(node.applicationStore.getApplications(gated.channelId)
				.isEmpty());
		assertEquals(0, node.settings.writes(APPLICATIONS_NS));
	}

	@Test
	public void aRepeatedAnnouncementWritesOnce() throws Exception {
		byte[] request = announceRequest(
				crypto.generateHybridSignatureKeyPair(), "Ann");
		assertTrue(ChannelTestNode.ackOk(node.handle(open.channelId,
				request)));
		assertTrue(ChannelTestNode.ackOk(node.handle(open.channelId,
				request)));
		assertTrue(ChannelTestNode.ackOk(node.handle(open.channelId,
				request)));
		assertEquals("a replay that changes nothing writes nothing", 1,
				node.settings.writes(SUBSCRIBERS_NS));
	}

	@Test
	public void announcementsStopWhenTheAllowanceIsSpentAndResume()
			throws Exception {
		int refused = 0;
		for (int i = 0; i < 300 && refused == 0; i++) {
			if (!announce(crypto.generateHybridSignatureKeyPair(),
					"S" + i)) {
				refused++;
			}
		}
		assertEquals("the write allowance runs out", 1, refused);
		int held = node.subscriberStore.getSubscribers(open.channelId)
				.size();
		assertTrue(held < 300);
		node.clock.advance(ChannelTestNode.HOUR);
		assertTrue("the allowance refills", announce(
				crypto.generateHybridSignatureKeyPair(), "Later"));
		assertEquals(held + 1, node.subscriberStore
				.getSubscribers(open.channelId).size());
	}

	@Test
	public void applicationsStopWhenTheAllowanceIsSpent() throws Exception {
		int refused = 0;
		for (int i = 0; i < 256 && refused == 0; i++) {
			if (!apply(crypto.generateHybridSignatureKeyPair(), "A" + i,
					eph(AGREEMENT_PUB))) {
				refused++;
			}
		}
		assertEquals("the write allowance runs out before the pending cap",
				1, refused);
		assertTrue(node.applicationStore.getApplications(gated.channelId)
				.size() < 256);
	}

	@Test
	public void aKnownSubscriberMayUpdateWhenTheListIsFull()
			throws Exception {
		KeyPair known = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) known.getPublic();
		List<ChannelSubscriber> full = new ArrayList<>();
		full.add(new ChannelSubscriber("Old", pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), 0L, false));
		for (int i = 1; i < MAX_SUBSCRIBERS; i++) {
			byte[] ed = new byte[32];
			ed[0] = (byte) i;
			ed[1] = (byte) (i >> 8);
			ed[2] = 1;
			full.add(new ChannelSubscriber("F" + i, ed, new byte[8], 0L,
					false));
		}
		Method write = ChannelSubscriberStore.class.getDeclaredMethod(
				"write", byte[].class, List.class);
		write.setAccessible(true);
		write.invoke(node.subscriberStore, open.channelId, full);
		assertFalse("a new subscriber is refused when full",
				announce(crypto.generateHybridSignatureKeyPair(), "New"));
		assertTrue("a known subscriber may update its row",
				announce(known, "Renamed"));
		List<ChannelSubscriber> subs =
				node.subscriberStore.getSubscribers(open.channelId);
		assertEquals(MAX_SUBSCRIBERS, subs.size());
		assertEquals("Renamed", subs.get(0).getDisplayName());
	}

	private boolean announce(KeyPair signer, String name) throws Exception {
		return ChannelTestNode.ackOk(node.handle(open.channelId,
				announceRequest(signer, name)));
	}

	private byte[] announceRequest(KeyPair signer, String name)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = node.clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		byte[] sig = node.signatures.signUserAnnounce(
				node.codec.announceSignedInput(open.channelId, name, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return node.pullCodec.encodeAnnounceRequest(open.channelId, name,
				ts, pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), sig,
				null, null);
	}

	private boolean apply(KeyPair signer, String name, byte[] eph)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) signer.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) signer.getPublic();
		long ts = node.clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		byte[] sig = node.signatures.signUserApplication(
				node.codec.applicationSignedInput(gated.channelId, name, ts,
						eph),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		byte[] request = node.pullCodec.encodeApplyRequest(gated.channelId,
				name, ts, pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(),
				eph, sig);
		return ChannelTestNode.ackOk(node.handle(gated.channelId, request));
	}

	private byte[] eph(int length) {
		byte[] b = new byte[length];
		random.nextBytes(b);
		return b;
	}

	private static String repeat(char c, int n) {
		char[] a = new char[n];
		Arrays.fill(a, c);
		return new String(a);
	}
}
