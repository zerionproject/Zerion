package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.request.DamagedLinkException;
import org.zerionproject.core.api.contact.request.IncompleteLinkException;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.util.Base32;

import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class InviteLinkCodecTest extends BrambleTestCase {

	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final InviteLinkCodec codec = new InviteLinkCodec(crypto);
	private final byte[] commitment = getRandomBytes(32);
	private final byte[] x25519 = getRandomBytes(32);
	private final String onion = crypto.encodeOnion(getRandomBytes(32));

	@Test
	public void aZeroIdRoundTripsWithItsInbox() throws Exception {
		String link = codec.format(1, commitment, x25519, onion);
		assertEquals(172, link.length());
		InviteLink parsed = codec.parse(link);
		assertEquals(InviteLink.Flow.REQUEST, parsed.getFlow());
		assertEquals(1, parsed.getType());
		assertEquals(onion, parsed.getInbox());
		assertArrayEquals(commitment, parsed.getCommitment());
		assertArrayEquals(x25519, parsed.getX25519());
	}

	@Test
	public void aPermanentLinkWithAnInboxIsARequestLink() throws Exception {
		InviteLink parsed =
				codec.parse(codec.format(2, commitment, x25519, onion));
		assertEquals(InviteLink.Flow.REQUEST, parsed.getFlow());
		assertEquals(2, parsed.getType());
	}

	@Test
	public void aVersionOneLinkRunsTheMutualFlow() throws Exception {
		InviteLink parsed =
				codec.parse(codec.format(1, commitment, x25519, null));
		assertEquals(InviteLink.Flow.MUTUAL_V1, parsed.getFlow());
		assertNull(parsed.getInbox());
	}

	@Test
	public void reservedParametersAreIgnored() throws Exception {
		String link = codec.format(1, commitment, x25519, null)
				+ "?name=x&i=" + onion + "&later";
		assertEquals(onion, codec.parse(link).getInbox());
		InviteLink noInbox = codec.parse(
				codec.format(1, commitment, x25519, null) + "?name=x");
		assertEquals(InviteLink.Flow.MUTUAL_V1, noInbox.getFlow());
	}

	@Test
	public void aDamagedInboxIsRejected() throws Exception {
		String body = codec.format(1, commitment, x25519, null);
		char last = onion.charAt(10) == 'a' ? 'b' : 'a';
		String wrongChecksum = onion.substring(0, 10) + last
				+ onion.substring(11);
		String[] bad = {"", onion.substring(1), onion + "a",
				wrongChecksum, onion.toUpperCase(Locale.US)};
		for (String i : bad) {
			try {
				codec.parse(body + "?i=" + i);
				fail(i);
			} catch (DamagedLinkException expected) {
			}
		}
		try {
			codec.parse(body + "?i=" + onion + "&i=" + onion);
			fail();
		} catch (DamagedLinkException expected) {
		}
	}

	@Test(expected = IncompleteLinkException.class)
	public void aPermanentLinkWithoutInboxIsIncomplete() throws Exception {
		codec.parse(codec.format(2, commitment, x25519, null));
	}

	@Test
	public void aClassicalLinkIsUnchanged() throws Exception {
		byte[] raw = getRandomBytes(33);
		raw[0] = 0;
		String link = "zerion://" + Base32.encode(raw).toLowerCase(Locale.US);
		assertEquals(9 + 53, link.length());
		InviteLink parsed = codec.parse(link);
		assertEquals(InviteLink.Flow.MUTUAL_CLASSICAL, parsed.getFlow());
	}

	@Test
	public void typesAboveTwoAreNewerVersionsReadUnsigned() throws Exception {
		for (int type : new int[] {3, 0x7F, 0x80, 0xFF}) {
			byte[] raw = getRandomBytes(65);
			raw[0] = (byte) type;
			String link = "zerion://"
					+ Base32.encode(raw).toLowerCase(Locale.US) + "?i=" + onion;
			try {
				codec.parse(link);
				fail("type " + type);
			} catch (UnsupportedVersionException e) {
				assertFalse(e.isTooOld());
			}
		}
	}

	@Test
	public void malformedLinksAreRejected() {
		String[] bad = {"zerion://abc", "briar://" + repeat('a', 104),
				"zerion://" + repeat('a', 103), "zerion://" + repeat('a', 105),
				"zerion://" + repeat('1', 104)};
		for (String link : bad) {
			try {
				codec.parse(link);
				fail(link);
			} catch (FormatException expected) {
			}
		}
	}

	private static String repeat(char c, int n) {
		char[] chars = new char[n];
		java.util.Arrays.fill(chars, c);
		return new String(chars);
	}

	@Test
	public void theSchemeMayBeUppercase() throws Exception {
		String link = codec.format(1, commitment, x25519, onion);
		InviteLink parsed = codec.parse("ZERION" + link.substring(6));
		assertEquals(onion, parsed.getInbox());
	}

	@Test
	public void onionValidationChecksTheChecksum() throws Exception {
		assertTrue(codec.isValidOnion(onion));
		assertArrayEquals(Base32.decode(onion, true).length == 35
				? java.util.Arrays.copyOf(Base32.decode(onion, true), 32)
				: null, codec.onionPublicKey(onion));
	}

	@Test
	public void aNewerTypeWinsOverADamagedInbox() throws Exception {
		byte[] raw = getRandomBytes(65);
		raw[0] = 3;
		String link = "zerion://" + Base32.encode(raw).toLowerCase(Locale.US)
				+ "?i=damaged";
		try {
			codec.parse(link);
			fail();
		} catch (UnsupportedVersionException e) {
			assertFalse(e.isTooOld());
		}
	}

	@Test
	public void aClassicalLinkIgnoresAnyInbox() throws Exception {
		byte[] raw = getRandomBytes(33);
		raw[0] = 0;
		String body = "zerion://" + Base32.encode(raw).toLowerCase(Locale.US);
		assertEquals(InviteLink.Flow.MUTUAL_CLASSICAL,
				codec.parse(body + "?i=damaged").getFlow());
		InviteLink withInbox = codec.parse(body + "?i=" + onion);
		assertEquals(InviteLink.Flow.MUTUAL_CLASSICAL, withInbox.getFlow());
		assertNull(withInbox.getInbox());
	}
}
