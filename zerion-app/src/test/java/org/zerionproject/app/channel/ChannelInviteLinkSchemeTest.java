package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelInviteLink;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelInviteLinkSchemeTest {

	private static final String[] SCHEMES = {
			"Zerion", "ZERION", "zErIoN", "ZeRiOn", "zerioN", "zERION"
	};

	private final Random random = new Random(18);
	private ChannelCodec codec;

	@Before
	public void setUp() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		codec = new ChannelCodec(crypto);
	}

	private String newLink(boolean approval, boolean publicChannel) {
		byte[] id = new byte[ChannelConstants.CHANNEL_ID_BYTES];
		random.nextBytes(id);
		byte[] pub = new byte[32];
		random.nextBytes(pub);
		byte[] cap = new byte[ChannelConstants.JOIN_CAPABILITY_BYTES];
		random.nextBytes(cap);
		StringBuilder onion = new StringBuilder();
		String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
		for (int i = 0; i < 56; i++) {
			onion.append(alphabet.charAt(random.nextInt(alphabet.length())));
		}
		return codec.formatInviteLink(id, pub, null, publicChannel,
				publicChannel || approval ? null : cap, onion.toString(),
				approval);
	}

	private static void assertSameLink(String scheme, ChannelInviteLink want,
			ChannelInviteLink got) {
		assertNotNull(scheme, got);
		assertArrayEquals(scheme, want.getChannelId(), got.getChannelId());
		assertArrayEquals(scheme, want.getPublisherEd25519PubKey(),
				got.getPublisherEd25519PubKey());
		assertArrayEquals(scheme, want.getJoinCapability(),
				got.getJoinCapability());
		assertEquals(scheme, want.getOnionAddress(), got.getOnionAddress());
		assertEquals(scheme, want.isPublicChannel(), got.isPublicChannel());
		assertEquals(scheme, want.requiresApproval(), got.requiresApproval());
	}

	@Test
	public void everyCaseOfTheSchemeOpensTheSameChannel() {
		String[] links = {
				newLink(false, true),
				newLink(false, false),
				newLink(true, false)
		};
		for (String lower : links) {
			assertTrue(lower.startsWith("zerion://channel/"));
			ChannelInviteLink want = codec.parseInviteLink(lower);
			assertNotNull(lower, want);
			String rest = lower.substring("zerion".length());
			for (String scheme : SCHEMES) {
				assertSameLink(scheme, want,
						codec.parseInviteLink(scheme + rest));
			}
		}
	}

	@Test
	public void onlyTheSchemeIsTreatedWithoutCase() {
		String lower = newLink(false, false);
		String afterHost = lower.substring("zerion://channel/".length());
		assertNull(codec.parseInviteLink("Zerion://CHANNEL/" + afterHost));
		String rest = lower.substring("zerion".length());
		assertNull(codec.parseInviteLink("zer\u0131on" + rest));
		assertNull(codec.parseInviteLink("ZER\u0130ON" + rest));
		assertNull(codec.parseInviteLink("briar" + rest));
		assertNull(codec.parseInviteLink("zerionx" + rest));
	}
}
