package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;

/**
 * The envelope decoder accepts exactly the encodings it produces: random
 * input, every single bit flip of a valid encoding and every truncation are
 * either refused with {@link FormatException} or decoded into an envelope
 * that re-encodes to the very same bytes, so no accepted input has a second
 * reading.
 */
public class AsyncEnvelopeFuzzTest {

	private static final int RANDOM_INPUTS = 3000;

	private final Random random = new Random(19);

	@Test
	public void randomInputIsRefusedOrCanonical() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] in = bytes(random.nextInt(AsyncEnvelope.HEADER_BYTES + 64));
			if (i % 3 == 0 && in.length > AsyncEnvelope.OFF_VERSION) {
				in[AsyncEnvelope.OFF_VERSION] = (byte) AsyncEnvelope.VERSION;
			}
			decode(in, "random input " + i);
		}
	}

	@Test
	public void everyBitFlipOfAValidEncodingIsRefusedOrCanonical() {
		byte[] enc = sample(bytes(40)).encode();
		for (int pos = 0; pos < enc.length; pos++) {
			for (int bit = 0; bit < 8; bit++) {
				byte[] mutated = enc.clone();
				mutated[pos] ^= (byte) (1 << bit);
				decode(mutated, "byte " + pos + " bit " + bit);
			}
		}
	}

	@Test
	public void everyTruncationIsRefusedOrCanonical() {
		byte[] enc = sample(bytes(24)).encode();
		for (int len = 0; len < enc.length; len++) {
			decode(Arrays.copyOf(enc, len), "truncated to " + len);
		}
	}

	private static void decode(byte[] in, String what) {
		AsyncEnvelope decoded;
		try {
			decoded = AsyncEnvelope.decode(in);
		} catch (FormatException expected) {
			return;
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": " + e, e);
		}
		assertArrayEquals(what, in, decoded.encode());
	}

	private AsyncEnvelope sample(byte[] aeadBlob) {
		return new AsyncEnvelope(AsyncEnvelope.PREKEY_KIND_ONE_TIME,
				bytes(AsyncEnvelope.PREKEY_ID_BYTES), 42L,
				bytes(AsyncEnvelope.EPHEMERAL_PUB_BYTES),
				bytes(AsyncEnvelope.KEM_CIPHERTEXT_BYTES), 3600L,
				bytes(AsyncEnvelope.DEDUP_ID_BYTES), aeadBlob);
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}
}
