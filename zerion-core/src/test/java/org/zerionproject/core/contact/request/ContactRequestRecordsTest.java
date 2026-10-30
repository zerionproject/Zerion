package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Record;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_ACK;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_FULL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_HELLO;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SEALED;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SLOTS_BUSY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class ContactRequestRecordsTest extends BrambleTestCase {

	@Test
	public void everyRecordHasItsExactSize() throws Exception {
		int[][] sizes = {{TYPE_HELLO, 43}, {TYPE_SOLUTION, 17},
				{TYPE_KEY, 1248}, {TYPE_FULL, 0}, {TYPE_SEALED, 3856},
				{TYPE_ACK, 32}, {TYPE_SLOTS_BUSY, 0}};
		for (int[] t : sizes) {
			assertEquals(t[1], ContactRequestRecords.sizeOf(t[0]));
			byte[] payload = getRandomBytes(t[1]);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ContactRequestRecords.write(out, t[0], payload);
			byte[] wire = out.toByteArray();
			assertEquals(6 + t[1], wire.length);
			assertEquals(1, wire[0]);
			assertEquals(t[0], wire[1]);
			Record r = ContactRequestRecords.read(
					new ByteArrayInputStream(wire), t[0]);
			assertEquals(t[0], r.type);
			assertArrayEquals(payload, r.payload);
		}
		assertEquals(-1, ContactRequestRecords.sizeOf(0));
		assertEquals(-1, ContactRequestRecords.sizeOf(8));
	}

	@Test
	public void aWrongLengthIsRefusedBeforeAnyPayloadByteIsRead()
			throws Exception {
		for (long length : new long[] {0, 42, 44, 0xFFFFFFFFL}) {
			byte[] header = {1, TYPE_HELLO, (byte) (length >> 24),
					(byte) (length >> 16), (byte) (length >> 8),
					(byte) length};
			CountingStream in = new CountingStream(header);
			try {
				ContactRequestRecords.read(in, TYPE_HELLO);
				fail();
			} catch (FormatException expected) {
			}
			assertEquals(6, in.read);
		}
	}

	@Test
	public void anUnexpectedTypeOrVersionIsRefused() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ContactRequestRecords.write(out, TYPE_ACK, getRandomBytes(32));
		byte[] wire = out.toByteArray();
		try {
			ContactRequestRecords.read(new ByteArrayInputStream(wire),
					TYPE_SOLUTION);
			fail();
		} catch (FormatException expected) {
		}
		wire[0] = 2;
		try {
			ContactRequestRecords.read(new ByteArrayInputStream(wire),
					TYPE_ACK);
			fail();
		} catch (FormatException expected) {
		}
	}

	@Test(expected = EOFException.class)
	public void aShortRecordIsAnEndOfStream() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ContactRequestRecords.write(out, TYPE_ACK, getRandomBytes(32));
		byte[] wire = out.toByteArray();
		byte[] cut = new byte[wire.length - 1];
		System.arraycopy(wire, 0, cut, 0, cut.length);
		ContactRequestRecords.read(new ByteArrayInputStream(cut), TYPE_ACK);
	}

	@Test(expected = IllegalArgumentException.class)
	public void writingAWrongSizeIsABug() throws Exception {
		ContactRequestRecords.write(new ByteArrayOutputStream(), TYPE_ACK,
				new byte[31]);
	}

	@Test
	public void helloAndSolutionRoundTrip() throws Exception {
		byte[] nonce = getRandomBytes(32);
		Hello h = Hello.decode(new Hello(1, 2, 20, 123456789L, nonce)
				.encode());
		assertEquals(2, h.level);
		assertEquals(20, h.difficulty);
		assertEquals(123456789L, h.epoch);
		assertArrayEquals(nonce, h.nonce);
		byte[] bad = new Hello(1, 2, 20, 1, nonce).encode();
		bad[0] = 2;
		try {
			Hello.decode(bad);
			fail();
		} catch (FormatException expected) {
		}
		byte[] x = getRandomBytes(8);
		Solution s = Solution.decode(new Solution(99, 3, x).encode());
		assertEquals(99, s.epoch);
		assertEquals(3, s.level);
		assertArrayEquals(x, s.x);
		byte[] key = ContactRequestRecords.encodeKey(getRandomBytes(1216),
				getRandomBytes(32));
		assertEquals(1248, key.length);
		assertEquals(1216,
				ContactRequestRecords.keyLinkPublicKey(key).length);
		assertEquals(32, ContactRequestRecords.keyEphemeral(key).length);
	}

	private static final class CountingStream extends InputStream {

		private final byte[] data;
		private int read = 0;

		private CountingStream(byte[] data) {
			this.data = data;
		}

		@Override
		public int read() throws IOException {
			if (read >= data.length) throw new AssertionError("over-read");
			return data[read++] & 0xFF;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) return 0;
			if (read >= data.length) throw new AssertionError("over-read");
			int n = Math.min(len, data.length - read);
			System.arraycopy(data, read, b, off, n);
			read += n;
			return n;
		}
	}
}
