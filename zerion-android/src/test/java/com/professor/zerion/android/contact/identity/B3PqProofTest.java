package com.professor.zerion.android.contact.identity;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static com.professor.zerion.android.contact.identity.B3PqProof.ROLE_ALICE;
import static com.professor.zerion.android.contact.identity.B3PqProof.ROLE_BOB;
import static com.professor.zerion.android.contact.identity.B3PqProof.SIG_INPUT_LEN;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * B.3 — In-band hybrid-key signing tests.
 *
 * Validates the byte layout, role-byte computation, sessionId derivation,
 * and Ed25519 sign/verify round-trip against the wire spec at
 * docs/wire/B3_B4_SPEC_v1.5.0.md. When iOS publishes the canonical test
 * vector (docs/wire/test_vectors/B3_v1.txt) we'll add an additional
 * test case here that pins our outputs to the canonical bytes.
 */
public class B3PqProofTest {

	// Deterministic local example vector — reproducible across runs via
	// the seeded SecureRandom. iOS will publish a canonical vector this
	// week; once it lands, we add a second test case keyed on those exact
	// inputs and assert byte-identical outputs.
	private static final long SEED = 0xC0FFEE_BABEL;

	@Test
	public void roleIsLowerLexEphemeral() {
		byte[] ephLow = bytes(0x00, 32);
		byte[] ephHigh = bytes(0xFF, 32);
		assertEquals(ROLE_ALICE, B3PqProof.roleFor(ephLow, ephHigh));
		assertEquals(ROLE_BOB, B3PqProof.roleFor(ephHigh, ephLow));
	}

	@Test
	public void roleHandlesUnsignedHighBitCorrectly() {
		// 0x7F vs 0x80 — the trap that catches signed-byte comparators.
		// In signed bytes, 0x80 = -128, which would compare as LESS THAN
		// 0x7F = +127. Unsigned: 0x7F (127) < 0x80 (128), so the all-0x7F
		// pubkey is Alice (lex-lower).
		byte[] ephSeven = bytes(0x7F, 32);
		byte[] ephEight = bytes(0x80, 32);
		assertEquals("0x7F should compare LESS THAN 0x80 unsigned",
				ROLE_ALICE, B3PqProof.roleFor(ephSeven, ephEight));
		assertEquals(ROLE_BOB, B3PqProof.roleFor(ephEight, ephSeven));
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
		byte[] input = B3PqProof.computeSigInput(ROLE_BOB, sessionId, pqPub);

		assertEquals("Total length per spec section 1.4 = 1251 bytes",
				SIG_INPUT_LEN, input.length);

		// Bytes 0..3:  uint32_BE(22)
		assertEquals(0x00, input[0]);
		assertEquals(0x00, input[1]);
		assertEquals(0x00, input[2]);
		assertEquals(0x16, input[3]); // 22 = 0x16

		// Bytes 4..25: "ZERION_PQ_KEY_PROOF_v1"
		assertArrayEquals("ZERION_PQ_KEY_PROOF_v1".getBytes(),
				Arrays.copyOfRange(input, 4, 26));

		// Byte 26: role
		assertEquals(ROLE_BOB, input[26]);

		// Bytes 27..30: uint32_BE(32)
		assertEquals(0x00, input[27]);
		assertEquals(0x00, input[28]);
		assertEquals(0x00, input[29]);
		assertEquals(0x20, input[30]); // 32 = 0x20

		// Bytes 31..62: sessionId
		assertArrayEquals(sessionId, Arrays.copyOfRange(input, 31, 63));

		// Bytes 63..66: uint32_BE(1184)
		assertEquals(0x00, input[63]);
		assertEquals(0x00, input[64]);
		assertEquals(0x04, input[65]); // 1184 = 0x04A0
		assertEquals((byte) 0xA0, input[66]);

		// Bytes 67..1250: pqPub
		assertArrayEquals(pqPub, Arrays.copyOfRange(input, 67, 1251));
	}

	@Test
	public void signVerifyRoundTrip() {
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(10));
		byte[] aliceEph = randomBytes(32, 11);
		byte[] bobEph = randomBytes(32, 12);
		byte[] bobPq = randomBytes(1184, 13);

		// Bob signs his binding.
		byte[] sig = B3PqProof.sign(signing.priv, bobEph, aliceEph, bobPq);
		assertEquals("Ed25519 sig is 64 bytes", 64, sig.length);

		// Alice verifies — passes Bob's eph as signerEph, her own as
		// verifierEph. Both sides compute sessionId + role identically.
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

		// Attacker swaps the PQ pubkey for a substitute.
		byte[] attackerPq = randomBytes(1184, 24);
		assertFalse("Substituted PQ pubkey must NOT verify",
				B3PqProof.verify(signing.pub, bobEph, aliceEph,
						attackerPq, sig));
	}

	@Test
	public void verifyRejectsTamperedRole() {
		// Generate two different sessions where the role byte differs and
		// confirm that a sig from one will not verify for the other.
		Ed25519KeyPair signing = Ed25519KeyPair.generate(seededRng(30));
		byte[] lowEph = bytes(0x10, 32);
		byte[] highEph = bytes(0xF0, 32);
		byte[] pq = randomBytes(1184, 31);

		// Sign as Alice (publisher's eph is lower).
		byte[] sigAlice = B3PqProof.sign(signing.priv, lowEph, highEph, pq);
		assertTrue(B3PqProof.verify(signing.pub, lowEph, highEph, pq, sigAlice));

		// A sig minted with role=Alice cannot verify under role=Bob.
		// Swapping the eph order in verify flips both role *and* sessionId
		// so the sig fails — exactly the desired property.
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
		// The whole point of the example vector — same inputs, same outputs,
		// every run, every machine. Ed25519 is deterministic; BLAKE2b is
		// deterministic; our byte layout is fixed. So this entire pipeline
		// is reproducible. Catches accidental nondeterminism in the
		// implementation (e.g. someone swapping in a randomized signer).
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
	public void domainSeparatorChangesSignature() {
		// Sanity: hand-computed signature on the same key but a one-byte
		// modification to the input must produce a different sig.
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

	// ---------- helpers ----------

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
		// SecureRandom seeded with a long is deterministic on the standard
		// SUN/SHA1PRNG provider, which is what we want for reproducible
		// test vectors.
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

	/** Ed25519 keypair generation via BC, decoupled from Bramble's
	 * crypto component so this test is pure unit / no DI. */
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
