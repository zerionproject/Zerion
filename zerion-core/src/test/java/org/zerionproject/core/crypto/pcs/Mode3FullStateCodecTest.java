package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_DECAPSULATION_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_VECTOR_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Mode3FullStateCodecTest {

	private final SecureRandom rnd = new SecureRandom();

	@Test
	public void roundTripWithPeerPkAndNoLruEntries() {
		Mode3FullState s = makeState(true, 0, 42L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		Mode3FullState back = Mode3FullStateCodec.decode(blob);
		assertNotNull(back);
		assertSame(s, back);
	}

	@Test
	public void roundTripFirstFrameSentinel() {
		Mode3FullState s = makeState(false, 0, 0L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		Mode3FullState back = Mode3FullStateCodec.decode(blob);
		assertNotNull(back);
		assertNull(back.getTheirActivePqPk());
		assertEquals(0L, back.getMessageCounter());
		assertSame(s, back);
	}

	@Test
	public void roundTripLargeLru() {
		Mode3FullState s = makeState(true, 32, 9876543210L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		Mode3FullState back = Mode3FullStateCodec.decode(blob);
		assertNotNull(back);
		assertEquals(32, back.getRecentKeyPairs().size());
		assertSame(s, back);
	}

	@Test
	public void kpIdLookupFindsCorrectKeypair() {
		Mode3FullState s = makeState(true, 5, 1L);
		KpId pick = s.getRecentKeyPairs().keySet().iterator().next();
		MlKemKeyPair found = s.findKeypairById(pick);
		assertNotNull(found);
		MlKemKeyPair expected = s.getRecentKeyPairs().get(pick);
		assertArrayEquals(expected.getDecapsulationKey(),
				found.getDecapsulationKey());
	}

	@Test
	public void kpIdLookupReturnsNullForUnknownId() {
		Mode3FullState s = makeState(true, 5, 1L);
		KpId unknown = new KpId(randomBytes(KpId.SIZE));
		assertNull(s.findKeypairById(unknown));
	}

	@Test
	public void kpIdLookupFindsCurrentKeypair() {
		Mode3FullState s = makeState(true, 5, 1L);
		KpId currentId = KpId.of(s.getOurActiveKeyPair().getEncapsulationKey());
		MlKemKeyPair found = s.findKeypairById(currentId);
		assertNotNull(found);
		assertArrayEquals(s.getOurActiveKeyPair().getDecapsulationKey(),
				found.getDecapsulationKey());
	}

	@Test
	public void rejectsCorruptedVersion() {
		Mode3FullState s = makeState(true, 1, 1L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		blob[0] = (byte) 0x99;
		assertNull(Mode3FullStateCodec.decode(blob));
	}

	@Test
	public void rejectsLegacyVersion1Blob() {
		Mode3FullState s = makeState(true, 1, 1L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		blob[0] = (byte) 0x01;
		assertNull(Mode3FullStateCodec.decode(blob));
	}

	@Test
	public void rejectsTruncated() {
		Mode3FullState s = makeState(true, 1, 1L);
		byte[] blob = Mode3FullStateCodec.encode(s);
		byte[] truncated = new byte[blob.length / 2];
		System.arraycopy(blob, 0, truncated, 0, truncated.length);
		assertNull(Mode3FullStateCodec.decode(truncated));
	}

	private Mode3FullState makeState(boolean withPeerPk, int lruEntries,
			long counter) {
		byte[] theirPk = withPeerPk
				? randomBytes(MLKEM_ENCAPSULATION_KEY_SIZE)
				: null;
		MlKemKeyPair ourKp = makeKeyPair();
		LinkedHashMap<KpId, MlKemKeyPair> recent = new LinkedHashMap<>();
		for (int i = 0; i < lruEntries; i++) {
			MlKemKeyPair kp = makeKeyPair();
			recent.put(KpId.of(kp.getEncapsulationKey()), kp);
		}
		return new Mode3FullState(theirPk, ourKp, recent, counter);
	}

	private MlKemKeyPair makeKeyPair() {
		byte[] seed = randomBytes(MLKEM_EK_SEED_SIZE);
		byte[] vec = randomBytes(MLKEM_EK_VECTOR_SIZE);
		byte[] dk = randomBytes(MLKEM_DECAPSULATION_KEY_SIZE);
		return MlKemKeyPair.fromComponents(seed, vec, dk);
	}

	private byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		rnd.nextBytes(b);
		return b;
	}

	private void assertSame(Mode3FullState a, Mode3FullState b) {
		assertArrayEquals(a.getTheirActivePqPk(), b.getTheirActivePqPk());
		assertSame(a.getOurActiveKeyPair(), b.getOurActiveKeyPair());
		assertEquals(a.getRecentKeyPairs().size(),
				b.getRecentKeyPairs().size());
		for (Map.Entry<KpId, MlKemKeyPair> e :
				a.getRecentKeyPairs().entrySet()) {
			MlKemKeyPair bKp = b.getRecentKeyPairs().get(e.getKey());
			assertNotNull(bKp);
			assertSame(e.getValue(), bKp);
		}
		assertTrue(b.getRecentKeyPairs().keySet().containsAll(
				a.getRecentKeyPairs().keySet()));
		assertEquals(a.getMessageCounter(), b.getMessageCounter());
	}

	private void assertSame(MlKemKeyPair a, MlKemKeyPair b) {
		assertArrayEquals(a.getEncapsulationKey(), b.getEncapsulationKey());
		assertArrayEquals(a.getDecapsulationKey(), b.getDecapsulationKey());
		assertArrayEquals(a.getEkSeed(), b.getEkSeed());
		assertArrayEquals(a.getEkVector(), b.getEkVector());
	}
}
