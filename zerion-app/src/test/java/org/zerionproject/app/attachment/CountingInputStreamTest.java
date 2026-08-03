package org.zerionproject.app.attachment;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CountingInputStreamTest extends BrambleTestCase {

	private final Random random = new Random();
	private final byte[] src = getRandomBytes(123);

	@Test
	public void testCountsSingleByteReads() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length + 1);

		assertEquals(0L, in.getBytesRead());

		for (int i = 0; i < src.length; i++) {
			assertEquals(i, in.getBytesRead());
			assertEquals(src[i] & 0xFF, in.read());
		}

		assertEquals(src.length, in.getBytesRead());

		assertEquals(-1, in.read());

		assertEquals(src.length, in.getBytesRead());
	}

	@Test
	public void testCountsMultiByteReads() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length + 1);

		assertEquals(0L, in.getBytesRead());

		byte[] dest = new byte[src.length];
		int offset = 0;
		while (offset < dest.length) {
			assertEquals(offset, in.getBytesRead());
			int length = Math.min(random.nextInt(10), dest.length - offset);
			assertEquals(length, in.read(dest, offset, length));
			offset += length;
		}

		assertArrayEquals(src, dest);

		assertEquals(src.length, in.getBytesRead());

		assertEquals(-1, in.read(dest, 0, 1));

		assertEquals(src.length, in.getBytesRead());
	}

	@Test
	public void testCountsSkips() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length + 1);

		assertEquals(0L, in.getBytesRead());

		int offset = 0;
		while (offset < src.length) {
			assertEquals(offset, in.getBytesRead());
			int length = Math.min(random.nextInt(10), src.length - offset);
			assertEquals(length, in.skip(length));
			offset += length;
		}

		assertEquals(src.length, in.getBytesRead());

		assertEquals(0, in.skip(1));

		assertEquals(src.length, in.getBytesRead());
	}

	@Test
	public void testReturnsEofWhenSingleByteReadReachesLimit()
			throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length - 1);

		for (int i = 0; i < src.length - 1; i++) {
			assertEquals(src[i] & 0xFF, in.read());
		}

		assertEquals(src.length - 1, in.getBytesRead());

		assertEquals(-1, in.read());

		assertEquals(src.length - 1, in.getBytesRead());
	}

	@Test
	public void testReturnsEofWhenMultiByteReadReachesLimit() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length - 1);

		byte[] dest = new byte[src.length];
		int offset = 0;
		while (offset < dest.length - 2) {
			int length = Math.min(random.nextInt(10), dest.length - 2 - offset);
			assertEquals(length, in.read(dest, offset, length));
			offset += length;
		}

		assertEquals(1, in.read(dest, offset, 2));

		for (int i = 0; i < src.length - 1; i++) assertEquals(src[i], dest[i]);

		assertEquals(src.length - 1, in.getBytesRead());

		assertEquals(-1, in.read(dest, 0, 1));

		assertEquals(src.length - 1, in.getBytesRead());
	}

	@Test
	public void testReturnsZeroWhenSkipReachesLimit() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);

		CountingInputStream in =
				new CountingInputStream(delegate, src.length - 1);

		int offset = 0;
		while (offset < src.length - 2) {
			assertEquals(offset, in.getBytesRead());
			int length = Math.min(random.nextInt(10), src.length - 2 - offset);
			assertEquals(length, in.skip(length));
			offset += length;
		}

		assertEquals(1, in.skip(2));

		assertEquals(src.length - 1, in.getBytesRead());

		assertEquals(0, in.skip(1));

		assertEquals(src.length - 1, in.getBytesRead());
	}

	@Test
	public void testMarkIsNotSupported() {
		InputStream delegate = new ByteArrayInputStream(src);
		CountingInputStream in = new CountingInputStream(delegate, src.length);
		assertFalse(in.markSupported());
	}

	@Test(expected = IOException.class)
	public void testResetIsNotSupported() throws Exception {
		InputStream delegate = new ByteArrayInputStream(src);
		CountingInputStream in = new CountingInputStream(delegate, src.length);
		in.mark(src.length);
		assertEquals(src.length, in.read(new byte[src.length]));
		in.reset();
	}
}
