package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_KEM_CT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_KP_ID_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_PK_ADVERTISE_SIZE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The header codec's decoders accept a well-formed header and otherwise throw
 * {@link PcsException}: random input, every single bit flip of a valid header
 * and every truncation length produce nothing else.
 */
public class PcsHeaderCodecFuzzTest {

	private static final int RANDOM_INPUTS = 3000;

	private final Random random = new Random(7);
	private final PcsHeaderCodec codec = new PcsHeaderCodec();

	@Test
	public void randomInputYieldsOnlyThePcsException() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] in = bytes(random.nextInt(2600));
			decodeBoth(in, "random input " + i);
		}
	}

	@Test
	public void everyBitFlipOfAMode2HeaderYieldsOnlyThePcsException()
			throws PcsException {
		byte[] header = codec.encodeMode2Header(123, 45,
				bytes(DH_PUBLIC_KEY_SIZE));
		assertEquals(123, codec.decode(header).getMessageNumber());
		forEachBitFlip(header);
	}

	@Test
	public void everyBitFlipOfAMode3FullHeaderYieldsOnlyThePcsException()
			throws PcsException {
		byte[] header = mode3FullHeader();
		assertEquals(77, codec.decodeMode3Full(header).getMessageNumber());
		forEachBitFlip(header);
	}

	@Test
	public void everyTruncationYieldsOnlyThePcsException() {
		byte[] mode2 = codec.encodeMode2Header(1, 2, bytes(DH_PUBLIC_KEY_SIZE));
		byte[] mode3 = mode3FullHeader();
		for (int len = 0; len < mode2.length; len++) {
			decodeBoth(Arrays.copyOf(mode2, len), "mode 2 truncated to " + len);
		}
		for (int len = 0; len < mode3.length; len++) {
			decodeBoth(Arrays.copyOf(mode3, len),
					"mode 3-full truncated to " + len);
		}
	}

	@Test
	public void acceptedMode3FullHeadersRoundTripTheirFields()
			throws PcsException {
		byte[] dh = bytes(DH_PUBLIC_KEY_SIZE);
		byte[] pk = bytes(MODE3_FULL_PK_ADVERTISE_SIZE);
		byte[] ct = bytes(MODE3_FULL_KEM_CT_SIZE);
		byte[] kpId = bytes(MODE3_FULL_KP_ID_SIZE);
		for (int i = 0; i < 50; i++) {
			int msg = random.nextInt(Integer.MAX_VALUE);
			int prev = random.nextInt(Integer.MAX_VALUE);
			byte[] header = codec.encodeMode3FullHeader(msg, prev, dh, pk, ct,
					kpId);
			PcsHeaderCodec.Mode3FullHeader d = codec.decodeMode3Full(header);
			assertEquals(msg, d.getMessageNumber());
			assertEquals(prev, d.getPreviousChainLength());
			assertArrayEquals(dh, d.getDhPublicKey());
		}
	}

	private byte[] mode3FullHeader() {
		return codec.encodeMode3FullHeader(77, 5, bytes(DH_PUBLIC_KEY_SIZE),
				bytes(MODE3_FULL_PK_ADVERTISE_SIZE), bytes(MODE3_FULL_KEM_CT_SIZE),
				bytes(MODE3_FULL_KP_ID_SIZE));
	}

	private void forEachBitFlip(byte[] header) {
		for (int pos = 0; pos < header.length; pos++) {
			for (int bit = 0; bit < 8; bit++) {
				byte[] mutated = header.clone();
				mutated[pos] ^= (byte) (1 << bit);
				decodeBoth(mutated, "byte " + pos + " bit " + bit);
			}
		}
	}

	private void decodeBoth(byte[] in, String what) {
		try {
			codec.decode(in);
		} catch (PcsException expected) {
		} catch (RuntimeException e) {
			throw new AssertionError(what + " (decode): " + e, e);
		}
		try {
			codec.decodeMode3Full(in);
		} catch (PcsException expected) {
		} catch (RuntimeException e) {
			throw new AssertionError(what + " (decodeMode3Full): " + e, e);
		}
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}
}
