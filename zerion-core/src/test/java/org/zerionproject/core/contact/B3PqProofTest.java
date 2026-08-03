package org.zerionproject.core.contact;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.zerionproject.core.api.contact.B3Constants.B3_ROLE_ALICE;
import static org.zerionproject.core.api.contact.B3Constants.B3_ROLE_BOB;
import static org.zerionproject.core.api.contact.B3Constants.B3_SIG_INPUT_LEN;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class B3PqProofTest {

	private static final long SEED = 0xC0FFEE_BABEL;

	@Test
	public void roleIsLowerLexEphemeral() {
		byte[] ephLow = bytes(0x00, 32);
		byte[] ephHigh = bytes(0xFF, 32);
		assertEquals(B3_ROLE_ALICE, B3PqProof.roleFor(ephLow, ephHigh));
		assertEquals(B3_ROLE_BOB, B3PqProof.roleFor(ephHigh, ephLow));
	}

	@Test
	public void roleHandlesUnsignedHighBitCorrectly() {

		byte[] ephSeven = bytes(0x7F, 32);
		byte[] ephEight = bytes(0x80, 32);
		assertEquals("0x7F should compare LESS THAN 0x80 unsigned",
				B3_ROLE_ALICE, B3PqProof.roleFor(ephSeven, ephEight));
		assertEquals(B3_ROLE_BOB, B3PqProof.roleFor(ephEight, ephSeven));
	}

	@Test
	public void sessionIdIsSymmetric() {
		byte[] ephA = randomBytes(32, 1);
		byte[] ephB = randomBytes(32, 2);
		byte[] sessionFromA = B3PqProof.computeSessionId(ephA, ephB);
		byte[] sessionFromB = B3PqProof.computeSessionId(ephB, ephA);
		assertArrayEquals("Both sides must derive the same sessionId",
				sessionFromA, sessionFromB);
		assertEquals("sessionId is BLAKE2b-256 — 32 bytes",
				32, sessionFromA.length);
	}

	@Test
	public void sessionIdChangesWithEphemeral() {
		byte[] ephA = randomBytes(32, 1);
		byte[] ephB = randomBytes(32, 2);
		byte[] ephC = randomBytes(32, 3);
		byte[] sessionAB = B3PqProof.computeSessionId(ephA, ephB);
		byte[] sessionAC = B3PqProof.computeSessionId(ephA, ephC);
		assertFalse("Different peer ephemerals must produce different sessionIds",
				Arrays.equals(sessionAB, sessionAC));
	}

	@Test
	public void sigInputMatchesSpecLayout() {
		byte[] sessionId = randomBytes(32, 4);
		byte[] pqPub = randomBytes(1184, 5);
		byte[] input = B3PqProof.computeSigInput(B3_ROLE_BOB, sessionId, pqPub);

		assertEquals("Total length per spec section 1.4 = 1251 bytes",
				B3_SIG_INPUT_LEN, input.length);

		assertEquals(0x00, input[0]);
		assertEquals(0x00, input[1]);
		assertEquals(0x00, input[2]);
		assertEquals(0x16, input[3]);

		assertArrayEquals("ZERION_PQ_KEY_PROOF_v1".getBytes(),
				Arrays.copyOfRange(input, 4, 26));

		assertEquals(B3_ROLE_BOB, input[26]);

		assertEquals(0x00, input[27]);
		assertEquals(0x00, input[28]);
		assertEquals(0x00, input[29]);
		assertEquals(0x20, input[30]);

		assertArrayEquals(sessionId, Arrays.copyOfRange(input, 31, 63));

		assertEquals(0x00, input[63]);
		assertEquals(0x00, input[64]);
		assertEquals(0x04, input[65]);
		assertEquals((byte) 0xA0, input[66]);

		assertArrayEquals(pqPub, Arrays.copyOfRange(input, 67, 1251));
	}

	@Test
	public void signVerifyRoundTrip() {
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(10));
		byte[] aliceEph = randomBytes(32, 11);
		byte[] bobEph = randomBytes(32, 12);
		byte[] bobPq = randomBytes(1184, 13);

		byte[] sig = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);
		assertEquals("Ed25519 sig is 64 bytes", 64, sig.length);

		assertTrue("Honest signature must verify",
				B3PqProof.verify(signing.pub, bobEph, aliceEph, bobPq, sig));
	}

	@Test
	public void verifyRejectsTamperedPqPubKey() {
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(20));
		byte[] aliceEph = randomBytes(32, 21);
		byte[] bobEph = randomBytes(32, 22);
		byte[] bobPq = randomBytes(1184, 23);
		byte[] sig = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);

		byte[] attackerPq = randomBytes(1184, 24);
		assertFalse("Substituted PQ pubkey must NOT verify",
				B3PqProof.verify(signing.pub, bobEph, aliceEph,
						attackerPq, sig));
	}

	@Test
	public void verifyRejectsTamperedRole() {

		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(30));
		byte[] lowEph = bytes(0x10, 32);
		byte[] highEph = bytes(0xF0, 32);
		byte[] pq = randomBytes(1184, 31);

		byte[] sigAlice = B3PqProof.sign(signing.priv, lowEph, highEph, pq);
		assertTrue(B3PqProof.verify(signing.pub, lowEph, highEph, pq, sigAlice));

		assertFalse(B3PqProof.verify(signing.pub, highEph, lowEph, pq,
				sigAlice));
	}

	@Test
	public void verifyRejectsWrongSigningKey() {
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(40));
		Ed25519KeyPair attacker = Ed25519KeyPair.generate(seededRng(41));
		byte[] aliceEph = randomBytes(32, 42);
		byte[] bobEph = randomBytes(32, 43);
		byte[] bobPq = randomBytes(1184, 44);
		byte[] sig = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);

		assertFalse("Different Ed25519 pubkey must NOT verify",
				B3PqProof.verify(attacker.pub, bobEph, aliceEph, bobPq, sig));
	}

	@Test
	public void verifyRejectsMalformedSignature() {
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(50));
		byte[] aliceEph = randomBytes(32, 51);
		byte[] bobEph = randomBytes(32, 52);
		byte[] bobPq = randomBytes(1184, 53);

		assertFalse("Null sig is rejected",
				B3PqProof.verify(signing.pub, bobEph, aliceEph, bobPq, null));
		assertFalse("Wrong-length sig is rejected",
				B3PqProof.verify(signing.pub, bobEph, aliceEph, bobPq,
						new byte[63]));
		assertFalse("Wrong-length sig is rejected",
				B3PqProof.verify(signing.pub, bobEph, aliceEph, bobPq,
						new byte[65]));
	}

	@Test
	public void deterministicVectorIsReproducible() {

		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(SEED));
		byte[] aliceEph = randomBytes(32, SEED + 1);
		byte[] bobEph = randomBytes(32, SEED + 2);
		byte[] bobPq = randomBytes(1184, SEED + 3);

		byte[] sig1 = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);
		byte[] sig2 = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);
		assertArrayEquals("Ed25519 must be deterministic", sig1, sig2);

		byte[] sessionId1 = B3PqProof.computeSessionId(aliceEph, bobEph);
		byte[] sessionId2 = B3PqProof.computeSessionId(aliceEph, bobEph);
		assertArrayEquals(sessionId1, sessionId2);
	}

	@Test
	public void canonicalVectorMatchesIOS() {

		byte[] aliceEph = hex(
				"1112131415161718191a1b1c1d1e1f20" +
				"2122232425262728292a2b2c2d2e2f30");
		byte[] bobEph = hex(
				"c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
				"d0d1d2d3d4d5d6d7d8d9dadbdcdddedf");
		byte[] bobSigningSeed = hex(
				"000102030405060708090a0b0c0d0e0f" +
				"101112131415161718191a1b1c1d1e1f");
		byte[] expectedBobSigningPub = hex(
				"03a107bff3ce10be1d70dd18e74bc099" +
				"67e4d6309ba50d5f1ddc8664125531b8");

		byte[] bobPq = new byte[1184];
		for (int i = 0; i < bobPq.length; i++) {
			bobPq[i] = (byte) ((i ^ 0xA5) & 0xFF);
		}

		byte expectedRoleByte = 0x02;
		byte[] expectedSessionId = hex(
				"9ddaa2c9b20ee986425a94bd5c8301a5" +
				"9e8910358890da831e620e93fa93cf0a");
		byte[] expectedSig = hex(
				"f28960d7a3da8fe71be0671fec3956b4" +
				"ee9c515ab68d325512fa2dcc1b058d06" +
				"2acaf2bd81f0ba38ef57dd13cbeafdf5" +
				"ac016370d17ee54e0b9a2d2cdfdd460a");

		org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters sk =
				new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
						bobSigningSeed, 0);
		byte[] derivedPub = sk.generatePublicKey().getEncoded();
		assertArrayEquals("Bob's signing pubkey derivation must match iOS",
				expectedBobSigningPub, derivedPub);

		byte role = B3PqProof.roleFor(bobEph, aliceEph);
		assertEquals("Canonical role byte (Bob's eph lex-greater than Alice's)",
				expectedRoleByte, role);

		byte[] sessionId = B3PqProof.computeSessionId(bobEph, aliceEph);
		assertArrayEquals("Canonical sessionId byte-equality across iOS/Android",
				expectedSessionId, sessionId);

		byte[] sig = B3PqProof.sign(bobSigningSeed, bobEph, aliceEph, bobPq);
		assertArrayEquals(
				"Canonical Ed25519 signature byte-equality (PyNaCl <-> BC)",
				expectedSig, sig);

		assertTrue("Canonical sig must verify on the Android side",
				B3PqProof.verify(expectedBobSigningPub,
						bobEph, aliceEph, bobPq, sig));
	}

	@Test
	public void domainSeparatorChangesSignature() {

		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(60));
		byte[] aliceEph = randomBytes(32, 61);
		byte[] bobEph = randomBytes(32, 62);
		byte[] pq1 = randomBytes(1184, 63);
		byte[] pq2 = pq1.clone();
		pq2[0] ^= 0x01;

		byte[] sig1 = B3PqProof.sign(signing.priv, bobEph, aliceEph, pq1);
		byte[] sig2 = B3PqProof.sign(signing.priv, bobEph, aliceEph, pq2);
		assertNotEquals("One bit flip in input must change the sig",
				toHex(sig1), toHex(sig2));
	}

	private static byte[] bytes(int value, int len) {
		byte[] b = new byte[len];
		Arrays.fill(b, (byte) (value & 0xFF));
		return b;
	}

	private static byte[] randomBytes(int len, long seed) {
		byte[] b = new byte[len];
		seededRng(seed).nextBytes(b);
		return b;
	}

	private static SecureRandom seededRng(long seed) {

		try {
			SecureRandom r = SecureRandom.getInstance("SHA1PRNG");
			r.setSeed(longToBytes(seed));
			return r;
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] longToBytes(long v) {
		byte[] out = new byte[8];
		for (int i = 7; i >= 0; i--) {
			out[i] = (byte) (v & 0xFF);
			v >>>= 8;
		}
		return out;
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
		return sb.toString();
	}

	private static byte[] hex(String s) {
		String clean = s.replaceAll("\\s+", "");
		if ((clean.length() & 1) != 0) {
			throw new IllegalArgumentException("odd-length hex string");
		}
		byte[] out = new byte[clean.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(
					clean.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static final class Ed25519KeyPair {
		final byte[] priv;
		final byte[] pub;

		Ed25519KeyPair(byte[] priv, byte[] pub) {
			this.priv = priv;
			this.pub = pub;
		}

		static Ed25519KeyPair generate(SecureRandom rng) {
			byte[] seed = new byte[32];
			rng.nextBytes(seed);
			org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters sk =
					new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(
							seed, 0);
			byte[] pub = sk.generatePublicKey().getEncoded();
			return new Ed25519KeyPair(seed, pub);
		}
	}
}
