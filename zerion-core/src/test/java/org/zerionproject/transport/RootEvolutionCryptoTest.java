package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RootEvolutionCryptoTest {

	private RootEvolutionTestBed bed;
	private RootEvolutionCrypto evolution;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		evolution = new RootEvolutionCrypto(bed.crypto);
	}

	private SecretKey nextRoot(SecretKey root, long epoch, byte[] kemSecret,
			SecretKey dh, byte[] transcript) {
		RootEvolutionCrypto.EvolvedKeys keys = evolution.evolve(
				RootEvolutionRecord.VERSION, root, epoch, kemSecret, dh,
				transcript);
		keys.confirmKey.clear();
		return keys.root;
	}

	private byte[] transcriptHash(long epoch, byte[] initiatorX25519,
			byte[] initiatorEk, byte[] responderX25519, byte[] ciphertext) {
		return evolution.transcriptHash(RootEvolutionRecord.VERSION, epoch,
				initiatorX25519, initiatorEk, responderX25519, ciphertext);
	}

	private byte[] responderConfirm(SecretKey next, long e) {
		return evolution.responderConfirmWith(
				evolution.legacyConfirmKey(next), e);
	}

	private byte[] initiatorConfirm(SecretKey next, long e) {
		return evolution.initiatorConfirmWith(
				evolution.legacyConfirmKey(next), e);
	}

	private byte[] responderDone(SecretKey next, long e) {
		return evolution.responderDoneWith(evolution.legacyConfirmKey(next),
				e);
	}

	private byte[] pendingId(SecretKey pending, long e) {
		return evolution.pendingIdWith(evolution.legacyConfirmKey(pending), e);
	}

	private boolean verifyResponderConfirm(byte[] mac, SecretKey next,
			long e) {
		return evolution.verifyResponderConfirmWith(mac,
				evolution.legacyConfirmKey(next), e);
	}

	private boolean verifyInitiatorConfirm(byte[] mac, SecretKey next,
			long e) {
		return evolution.verifyInitiatorConfirmWith(mac,
				evolution.legacyConfirmKey(next), e);
	}

	private boolean verifyResponderDone(byte[] mac, SecretKey next, long e) {
		return evolution.verifyResponderDoneWith(mac,
				evolution.legacyConfirmKey(next), e);
	}

	@Test
	public void anEvolvedRootIsUsedOnlyThroughSubkeys() throws Exception {
		SecretKey root = bed.randomKey();
		assertSame(root, evolution.transportRoot(root, 0));

		SecretKey transport1 = evolution.transportRoot(root, 1);
		SecretKey transport2 = evolution.transportRoot(root, 2);
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript = transcriptHash(1, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		SecretKey next = nextRoot(root, 1, kemSecret, dh,
				transcript);

		Set<String> keys = new HashSet<>();
		keys.add(Arrays.toString(root.getBytes()));
		keys.add(Arrays.toString(transport1.getBytes()));
		keys.add(Arrays.toString(transport2.getBytes()));
		keys.add(Arrays.toString(next.getBytes()));
		assertEquals(4, keys.size());

		byte[] responderConfirm = responderConfirm(next, 2);
		byte[] initiatorConfirm = initiatorConfirm(next, 2);
		byte[] responderDone = responderDone(next, 2);
		byte[] pendingId = pendingId(next, 2);
		Set<String> proofs = new HashSet<>();
		proofs.add(Arrays.toString(responderConfirm));
		proofs.add(Arrays.toString(initiatorConfirm));
		proofs.add(Arrays.toString(responderDone));
		proofs.add(Arrays.toString(pendingId));
		assertEquals(4, proofs.size());

		assertTrue(verifyResponderConfirm(responderConfirm, next, 2));
		assertFalse(verifyResponderConfirm(initiatorConfirm, next,
				2));
		assertFalse(verifyInitiatorConfirm(responderConfirm, next,
				2));
		assertFalse(verifyResponderDone(responderConfirm, next, 2));
		assertFalse(verifyResponderConfirm(responderConfirm, next,
				3));
		assertFalse(verifyResponderConfirm(responderConfirm, root,
				2));
	}

	@Test
	public void theNextRootDependsOnEveryFreshInput() throws Exception {
		SecretKey root = bed.randomKey();
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript = transcriptHash(0, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		byte[] base = nextRoot(root, 0, kemSecret, dh, transcript)
				.getBytes();

		byte[] otherKem = kemSecret.clone();
		otherKem[0] ^= 1;
		assertFalse(Arrays.equals(base, nextRoot(root, 0, otherKem,
				dh, transcript).getBytes()));
		assertFalse(Arrays.equals(base, nextRoot(root, 0, kemSecret,
				bed.randomKey(), transcript).getBytes()));
		byte[] otherTranscript = transcriptHash(0, new byte[32],
				new byte[1184], new byte[32], new byte[1087]);
		assertFalse(Arrays.equals(base, nextRoot(root, 0, kemSecret,
				dh, otherTranscript).getBytes()));
		assertFalse(Arrays.equals(base, nextRoot(bed.randomKey(), 0,
				kemSecret, dh, transcript).getBytes()));
		assertFalse(Arrays.equals(base, nextRoot(root, 1, kemSecret,
				dh, transcript).getBytes()));
	}

	@Test
	public void versionTwoKeepsTheDerivationOfEarlierReleases()
			throws Exception {
		SecretKey root = bed.randomKey();
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript = transcriptHash(4, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		assertArrayEquals(bed.crypto.hash(RootEvolutionCrypto.LABEL_TRANSCRIPT,
				new byte[] {2}, RootEvolutionCrypto.u64(4), new byte[32],
				new byte[1184], new byte[32], new byte[1088]), transcript);
		SecretKey chain = bed.crypto.deriveKey(
				RootEvolutionCrypto.LABEL_EPOCH_CHAIN, root,
				RootEvolutionCrypto.u64(4));
		SecretKey expectedRoot = bed.crypto.deriveKey(
				RootEvolutionCrypto.LABEL_ROOT, chain,
				RootEvolutionCrypto.u64(5), kemSecret, dh.getBytes(),
				transcript);
		SecretKey expectedConfirm = bed.crypto.deriveKey(
				RootEvolutionCrypto.LABEL_CONFIRM_KEY, expectedRoot);
		RootEvolutionCrypto.EvolvedKeys keys = evolution.evolve(
				RootEvolutionRecord.VERSION, root, 4, kemSecret, dh,
				transcript);
		assertFalse(keys.isSeparated());
		assertArrayEquals(expectedRoot.getBytes(), keys.root.getBytes());
		assertArrayEquals(expectedConfirm.getBytes(),
				keys.confirmKey.getBytes());
		assertArrayEquals(bed.crypto.mac(
						RootEvolutionCrypto.LABEL_RESPONDER_CONFIRM,
						expectedConfirm, RootEvolutionCrypto.u64(5)),
				responderConfirm(keys.root, 5));
		assertArrayEquals(pendingId(keys.root, 5),
				evolution.pendingIdWith(keys.confirmKey, 5));
	}

	@Test
	public void versionThreeNeverUsesTheNewRootAsAKey() throws Exception {
		SecretKey root = bed.randomKey();
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript2 = evolution.transcriptHash(
				RootEvolutionRecord.VERSION, 4, new byte[32], new byte[1184],
				new byte[32], new byte[1088]);
		byte[] transcript3 = evolution.transcriptHash(
				RootEvolutionRecord.VERSION_SEPARATED, 4, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		assertFalse(Arrays.equals(transcript2, transcript3));
		RootEvolutionCrypto.EvolvedKeys v3 = evolution.evolve(
				RootEvolutionRecord.VERSION_SEPARATED, root, 4, kemSecret, dh,
				transcript3);
		RootEvolutionCrypto.EvolvedKeys v2 = evolution.evolve(
				RootEvolutionRecord.VERSION, root, 4, kemSecret, dh,
				transcript2);
		assertTrue(v3.isSeparated());
		assertFalse(Arrays.equals(v2.root.getBytes(), v3.root.getBytes()));
		assertFalse(Arrays.equals(v3.root.getBytes(),
				v3.confirmKey.getBytes()));
		SecretKey fromRoot = evolution.legacyConfirmKey(v3.root);
		assertFalse("the confirm key must not be derived from the new root",
				Arrays.equals(fromRoot.getBytes(), v3.confirmKey.getBytes()));
		SecretKey chain = bed.crypto.deriveKey(
				RootEvolutionCrypto.LABEL_EPOCH_CHAIN, root,
				RootEvolutionCrypto.u64(4));
		assertArrayEquals(bed.crypto.deriveKey(
				RootEvolutionCrypto.LABEL_SEPARATED_CONFIRM_KEY, chain,
				RootEvolutionCrypto.u64(5), kemSecret, dh.getBytes(),
				transcript3).getBytes(), v3.confirmKey.getBytes());

		byte[] proof = evolution.responderConfirmWith(v3.confirmKey, 5);
		assertTrue(evolution.verifyResponderConfirmWith(proof, v3.confirmKey,
				5));
		assertFalse(verifyResponderConfirm(proof, v3.root, 5));
		ContactRootKeys pending = new ContactRootKeys(4, root, v3.root,
				v3.confirmKey, true);
		assertEquals(RootEvolutionRecord.VERSION_SEPARATED,
				RootEvolutionCrypto.pendingVersion(pending));
		assertArrayEquals(evolution.pendingIdWith(v3.confirmKey, 5),
				evolution.pendingIdWith(evolution.pendingConfirmKey(pending),
						5));
		assertFalse(Arrays.equals(pendingId(v3.root, 5),
				evolution.pendingIdWith(v3.confirmKey, 5)));
		ContactRootKeys legacy = new ContactRootKeys(4, root, v2.root, true);
		assertEquals(RootEvolutionRecord.VERSION,
				RootEvolutionCrypto.pendingVersion(legacy));
		assertArrayEquals(v2.confirmKey.getBytes(),
				evolution.pendingConfirmKey(legacy).getBytes());
	}

	@Test
	public void separatedRecordsCarryTheirVersion() throws Exception {
		byte[] mac = bed.randomKey().getBytes();
		byte[] confirm = RootEvolutionRecord.confirm(
				RootEvolutionRecord.VERSION_SEPARATED, 3, mac);
		RootEvolutionRecord r = RootEvolutionRecord.decode(confirm);
		assertNotNull(r);
		assertEquals(RootEvolutionRecord.VERSION_SEPARATED, r.version);
		assertEquals(RootEvolutionRecord.KIND_CONFIRM, r.kind);
		assertArrayEquals(mac, r.a);
		RootEvolutionRecord legacy = RootEvolutionRecord.decode(
				RootEvolutionRecord.confirm(3, mac));
		assertNotNull(legacy);
		assertEquals(RootEvolutionRecord.VERSION, legacy.version);
		byte[] identity = RootEvolutionRecord.identity(
				new byte[RootEvolutionRecord.ML_DSA_PUBLIC_KEY_LENGTH],
				new byte[RootEvolutionRecord.ED25519_SIGNATURE_LENGTH])[0];
		identity[0] = RootEvolutionRecord.VERSION_SEPARATED;
		try {
			RootEvolutionRecord.decode(identity);
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test
	public void recordsOfAnotherVersionAreIgnoredAndMalformedOnesRefused()
			throws Exception {
		byte[] mac = bed.randomKey().getBytes();
		byte[] hello = RootEvolutionRecord.hello(7, mac, (byte) 0);
		RootEvolutionRecord r = RootEvolutionRecord.decode(hello);
		assertNotNull(r);
		assertEquals(RootEvolutionRecord.KIND_HELLO, r.kind);
		assertEquals(7, r.epoch);
		assertArrayEquals(mac, r.a);

		byte[] otherVersion = hello.clone();
		otherVersion[0] = 4;
		assertNull(RootEvolutionRecord.decode(otherVersion));
		byte[] separatedHello = hello.clone();
		separatedHello[0] = RootEvolutionRecord.VERSION_SEPARATED;
		try {
			RootEvolutionRecord.decode(separatedHello);
			fail();
		} catch (FormatException expected) {
		}
		byte[] developmentVersion = hello.clone();
		developmentVersion[0] = 1;
		assertNull(RootEvolutionRecord.decode(developmentVersion));

		byte[] truncated = Arrays.copyOf(hello, hello.length - 1);
		try {
			RootEvolutionRecord.decode(truncated);
			fail();
		} catch (FormatException expected) {
		}
		byte[] unknownKind = hello.clone();
		unknownKind[1] = 9;
		try {
			RootEvolutionRecord.decode(unknownKind);
			fail();
		} catch (FormatException expected) {
		}
		byte[] negativeEpoch = hello.clone();
		negativeEpoch[2] = (byte) 0x80;
		try {
			RootEvolutionRecord.decode(negativeEpoch);
			fail();
		} catch (FormatException expected) {
		}
	}
}
