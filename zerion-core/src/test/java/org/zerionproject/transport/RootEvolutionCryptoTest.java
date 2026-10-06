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

	@Test
	public void anEvolvedRootIsUsedOnlyThroughSubkeys() throws Exception {
		SecretKey root = bed.randomKey();
		assertSame(root, evolution.transportRoot(root, 0));

		SecretKey transport1 = evolution.transportRoot(root, 1);
		SecretKey transport2 = evolution.transportRoot(root, 2);
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript = evolution.transcriptHash(1, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		SecretKey next = evolution.nextRoot(root, 1, kemSecret, dh,
				transcript);

		Set<String> keys = new HashSet<>();
		keys.add(Arrays.toString(root.getBytes()));
		keys.add(Arrays.toString(transport1.getBytes()));
		keys.add(Arrays.toString(transport2.getBytes()));
		keys.add(Arrays.toString(next.getBytes()));
		assertEquals(4, keys.size());

		byte[] responderConfirm = evolution.responderConfirm(next, 2);
		byte[] initiatorConfirm = evolution.initiatorConfirm(next, 2);
		byte[] responderDone = evolution.responderDone(next, 2);
		byte[] pendingId = evolution.pendingId(next, 2);
		Set<String> proofs = new HashSet<>();
		proofs.add(Arrays.toString(responderConfirm));
		proofs.add(Arrays.toString(initiatorConfirm));
		proofs.add(Arrays.toString(responderDone));
		proofs.add(Arrays.toString(pendingId));
		assertEquals(4, proofs.size());

		assertTrue(evolution.verifyResponderConfirm(responderConfirm, next, 2));
		assertFalse(evolution.verifyResponderConfirm(initiatorConfirm, next,
				2));
		assertFalse(evolution.verifyInitiatorConfirm(responderConfirm, next,
				2));
		assertFalse(evolution.verifyResponderDone(responderConfirm, next, 2));
		assertFalse(evolution.verifyResponderConfirm(responderConfirm, next,
				3));
		assertFalse(evolution.verifyResponderConfirm(responderConfirm, root,
				2));
	}

	@Test
	public void theNextRootDependsOnEveryFreshInput() throws Exception {
		SecretKey root = bed.randomKey();
		byte[] kemSecret = bed.randomKey().getBytes();
		SecretKey dh = bed.randomKey();
		byte[] transcript = evolution.transcriptHash(0, new byte[32],
				new byte[1184], new byte[32], new byte[1088]);
		byte[] base = evolution.nextRoot(root, 0, kemSecret, dh, transcript)
				.getBytes();

		byte[] otherKem = kemSecret.clone();
		otherKem[0] ^= 1;
		assertFalse(Arrays.equals(base, evolution.nextRoot(root, 0, otherKem,
				dh, transcript).getBytes()));
		assertFalse(Arrays.equals(base, evolution.nextRoot(root, 0, kemSecret,
				bed.randomKey(), transcript).getBytes()));
		byte[] otherTranscript = evolution.transcriptHash(0, new byte[32],
				new byte[1184], new byte[32], new byte[1087]);
		assertFalse(Arrays.equals(base, evolution.nextRoot(root, 0, kemSecret,
				dh, otherTranscript).getBytes()));
		assertFalse(Arrays.equals(base, evolution.nextRoot(bed.randomKey(), 0,
				kemSecret, dh, transcript).getBytes()));
		assertFalse(Arrays.equals(base, evolution.nextRoot(root, 1, kemSecret,
				dh, transcript).getBytes()));
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
		otherVersion[0] = 3;
		assertNull(RootEvolutionRecord.decode(otherVersion));
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
