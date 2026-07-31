package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.ByteUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AsyncEnvelopeTest {

	private final Random random = new Random(1);

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

	@Test
	public void roundTripPreservesEveryField() throws FormatException {
		byte[] blob = bytes(200);
		AsyncEnvelope e = sample(blob);
		AsyncEnvelope d = AsyncEnvelope.decode(e.encode());
		assertEquals(e.getPrekeyKind(), d.getPrekeyKind());
		assertArrayEquals(e.getPrekeyId(), d.getPrekeyId());
		assertEquals(e.getSignedPrekeyId(), d.getSignedPrekeyId());
		assertArrayEquals(e.getSenderEphemeralPub(), d.getSenderEphemeralPub());
		assertArrayEquals(e.getKemCiphertext(), d.getKemCiphertext());
		assertEquals(e.getTtl(), d.getTtl());
		assertArrayEquals(e.getDedupId(), d.getDedupId());
		assertArrayEquals(e.getAeadBlob(), d.getAeadBlob());
	}

	@Test
	public void encodedLengthMatchesHeaderPlusBlob() {
		byte[] blob = bytes(123);
		assertEquals(AsyncEnvelope.HEADER_BYTES + blob.length,
				sample(blob).encode().length);
	}

	@Test(expected = FormatException.class)
	public void rejectsTruncatedHeader() throws FormatException {
		AsyncEnvelope.decode(new byte[AsyncEnvelope.HEADER_BYTES - 1]);
	}

	@Test
	public void rejectsBadVersion() {
		byte[] enc = sample(bytes(16)).encode();
		enc[AsyncEnvelope.OFF_VERSION] = 0x02;
		expectFormatException(enc);
	}

	@Test
	public void rejectsUnknownPrekeyKind() {
		byte[] enc = sample(bytes(16)).encode();
		enc[AsyncEnvelope.OFF_PREKEY_KIND] = 0x7F;
		expectFormatException(enc);
	}

	@Test
	public void rejectsTrailingBytes() {
		byte[] enc = sample(bytes(16)).encode();
		expectFormatException(Arrays.copyOf(enc, enc.length + 1));
	}

	@Test
	public void rejectsCiphertextLenBeyondCap() {
		byte[] enc = sample(bytes(16)).encode();
		ByteUtils.writeUint32(AsyncEnvelope.MAX_AEAD_BLOB_BYTES + 1L, enc,
				AsyncEnvelope.OFF_CIPHERTEXT_LEN);
		expectFormatException(enc);
	}

	private void expectFormatException(byte[] enc) {
		try {
			AsyncEnvelope.decode(enc);
			fail("expected FormatException");
		} catch (FormatException expected) {
			// ok
		}
	}
}
