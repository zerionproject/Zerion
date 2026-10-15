package com.professor.zerion.android.backup;

import com.professor.zerion.android.vault.crypto.Argon2;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupStreamTest {

	private static final int CHUNK = BackupStream.MIN_CHUNK;
	private static final int SEALED_CHUNK = CHUNK + BackupStream.TAG_LENGTH;
	private static final Argon2.Argon2Params PARAMS =
			new Argon2.Argon2Params(Argon2.LOW_MEMORY_KB, 1, 1,
					Argon2.DEFAULT_HASH_LENGTH);

	private final byte[] key = random(32, 1);

	private static byte[] random(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	private static BackupStream.Header header(long length) {
		return header(length, CHUNK);
	}

	private static BackupStream.Header header(long length, int chunk) {
		return BackupStream.newHeader((byte) 0, new byte[32], PARAMS, chunk,
				length);
	}

	private byte[] seal(BackupStream.Header h, byte[] plain) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BackupStream.writeChunks(new ByteArrayInputStream(plain), h, key, out);
		return out.toByteArray();
	}

	private byte[] open(BackupStream.Header h, byte[] chunks)
			throws IOException, BackupException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BackupStream.readChunks(new ByteArrayInputStream(chunks), h, key, out);
		return out.toByteArray();
	}

	private void assertCorrupt(BackupStream.Header h, byte[] chunks) {
		try {
			open(h, chunks);
			fail("a changed stream was accepted");
		} catch (BackupException e) {
			assertEquals(CORRUPT, e.reason);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] chunk(byte[] chunks, int i) {
		return Arrays.copyOfRange(chunks, i * SEALED_CHUNK,
				Math.min(chunks.length, (i + 1) * SEALED_CHUNK));
	}

	private static byte[] join(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	@Test
	public void lengthsAroundChunkBoundariesRoundTrip() throws Exception {
		for (int len : new int[] {1, 2, CHUNK - 1, CHUNK, CHUNK + 1,
				3 * CHUNK, 3 * CHUNK + 5}) {
			byte[] plain = random(len, len);
			BackupStream.Header h = header(len);
			byte[] chunks = seal(h, plain);
			assertEquals((h.chunkCount() * BackupStream.TAG_LENGTH) + len,
					chunks.length);
			assertArrayEquals(plain, open(h, chunks));
		}
	}

	@Test
	public void aStreamCutAtAnyByteIsRefused() throws Exception {
		int len = 3 * CHUNK + 100;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 7));
		for (int cut = 0; cut < chunks.length; cut++) {
			assertCorrupt(h, Arrays.copyOf(chunks, cut));
		}
	}

	@Test
	public void aStreamEndingAtAChunkBoundaryIsRefused() throws Exception {
		int len = 4 * CHUNK;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 8));
		for (int keep = 0; keep < 4; keep++) {
			assertCorrupt(h, Arrays.copyOf(chunks, keep * SEALED_CHUNK));
		}
	}

	@Test
	public void reorderedChunksAreRefused() throws Exception {
		int len = 3 * CHUNK + 10;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 9));
		byte[] c0 = chunk(chunks, 0);
		byte[] c1 = chunk(chunks, 1);
		byte[] c2 = chunk(chunks, 2);
		byte[] c3 = chunk(chunks, 3);
		assertCorrupt(h, join(c1, c0, c2, c3));
		assertCorrupt(h, join(c0, c2, c1, c3));
	}

	@Test
	public void duplicatedOrInsertedChunksAreRefused() throws Exception {
		int len = 3 * CHUNK + 10;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 10));
		byte[] c0 = chunk(chunks, 0);
		byte[] c1 = chunk(chunks, 1);
		byte[] c2 = chunk(chunks, 2);
		byte[] c3 = chunk(chunks, 3);
		assertCorrupt(h, join(c0, c0, c2, c3));
		assertCorrupt(h, join(c0, c0, c1, c2, c3));
		assertCorrupt(h, join(c0, c1, c2, c3, c3));
		assertCorrupt(h, join(c0, c1, c2, c2));
	}

	@Test
	public void anExtendedStreamIsRefused() throws Exception {
		int len = 2 * CHUNK;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 11));
		assertCorrupt(h, join(chunks, new byte[] {0}));
		assertCorrupt(h, join(chunks, chunk(chunks, 1)));
	}

	@Test
	public void anyChangedByteIsRefused() throws Exception {
		int len = 2 * CHUNK + 33;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 12));
		for (int i = 0; i < chunks.length; i += 97) {
			byte[] changed = chunks.clone();
			changed[i] ^= 0x01;
			assertCorrupt(h, changed);
		}
		byte[] lastTag = chunks.clone();
		lastTag[lastTag.length - 1] ^= (byte) 0x80;
		assertCorrupt(h, lastTag);
	}

	@Test
	public void aChangedHeaderFailsTheFirstChunk() throws Exception {
		int len = 2 * CHUNK + 33;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 13));
		for (int i = 0; i < BackupStream.HEADER_LENGTH; i++) {
			byte[] encoded = h.encoded.clone();
			encoded[i] ^= 0x01;
			BackupStream.Header changed;
			try {
				changed = BackupStream.parse(encoded);
			} catch (BackupException e) {
				assertEquals(CORRUPT, e.reason);
				continue;
			}
			assertCorrupt(changed, chunks);
		}
		assertCorrupt(header(len - 1), chunks);
		assertCorrupt(header(len + 1), chunks);
	}

	@Test
	public void theWrongKeyIsRefused() throws Exception {
		int len = CHUNK + 1;
		BackupStream.Header h = header(len);
		byte[] chunks = seal(h, random(len, 14));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			BackupStream.readChunks(new ByteArrayInputStream(chunks), h,
					random(32, 99), out);
			fail("the wrong key opened the stream");
		} catch (BackupException e) {
			assertEquals(CORRUPT, e.reason);
		}
		assertEquals("nothing was released before the tag checked out",
				0, out.size());
	}

	@Test
	public void aSourceThatDoesNotMatchTheDeclaredLengthIsNotWritten() {
		BackupStream.Header h = header(CHUNK + 10);
		for (byte[] wrong : new byte[][] {random(CHUNK + 9, 1),
				random(CHUNK + 11, 1)}) {
			try {
				BackupStream.writeChunks(new ByteArrayInputStream(wrong), h,
						key, new ByteArrayOutputStream());
				fail("a source of the wrong length was sealed");
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void headersOutsideTheAcceptedRangeAreRefused() {
		BackupStream.Header h = header(10);
		int chunkAt = BackupStream.HEADER_LENGTH - 12;
		int lengthAt = BackupStream.HEADER_LENGTH - 8;
		long[][] cases = {
				{chunkAt, BackupStream.MIN_CHUNK - 1, 4},
				{chunkAt, BackupStream.MAX_CHUNK + 1, 4},
				{chunkAt, -1, 4},
				{lengthAt, 0, 8},
				{lengthAt, -1, 8},
				{lengthAt, BackupStream.MAX_LENGTH + 1, 8},
		};
		for (long[] c : cases) {
			byte[] encoded = h.encoded.clone();
			java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(encoded);
			if (c[2] == 4) b.putInt((int) c[0], (int) c[1]);
			else b.putLong((int) c[0], c[1]);
			try {
				BackupStream.parse(encoded);
				fail("accepted " + Arrays.toString(c));
			} catch (BackupException e) {
				assertEquals(CORRUPT, e.reason);
			}
		}
	}

	@Test
	public void memoryStaysTheSameWhateverTheSize() throws Exception {
		int chunk = 1 << 20;
		roundTrip(4L << 20, chunk);
		long[] small = roundTrip(4L << 20, chunk);
		long[] large = roundTrip(64L << 20, chunk);
		for (int i = 0; i < 2; i++) {
			String what = (i == 0 ? "sealing" : "opening") + ": 4 MiB took "
					+ small[i] + " bytes, 64 MiB took " + large[i];
			assertTrue(what, large[i] < small[i] + (1 << 20));
			assertTrue(what, large[i] < 8L * chunk);
		}
	}

	private long[] roundTrip(long length, int chunk) throws Exception {
		ThreadAllocations allocations = ThreadAllocations.require();
		BackupStream.Header h = header(length, chunk);
		java.io.File file = java.io.File.createTempFile("zbk-test", ".v3");
		try {
			long before = allocations.now();
			try (OutputStream out = new java.io.FileOutputStream(file)) {
				BackupStream.writeChunks(new Pattern(length), h, key, out);
			}
			long sealing = allocations.now() - before;
			assertEquals(length + h.chunkCount() * BackupStream.TAG_LENGTH,
					file.length());
			ByteCounter opened = new ByteCounter();
			before = allocations.now();
			try (InputStream in = new java.io.FileInputStream(file)) {
				BackupStream.readChunks(in, h, key, opened);
			}
			long opening = allocations.now() - before;
			assertEquals(length, opened.count);
			return new long[] {sealing, opening};
		} finally {
			assertTrue(file.delete());
		}
	}

	private static final class Pattern extends InputStream {

		private long remaining;

		Pattern(long length) {
			remaining = length;
		}

		@Override
		public int read() {
			if (remaining == 0) return -1;
			remaining--;
			return (int) (remaining & 0xFF);
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (remaining == 0) return -1;
			int n = (int) Math.min(len, remaining);
			for (int i = 0; i < n; i++) b[off + i] = (byte) (remaining - i);
			remaining -= n;
			return n;
		}
	}

	private static final class ByteCounter extends OutputStream {

		long count;

		@Override
		public void write(int b) {
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) {
			count += len;
		}
	}
}
