package com.professor.zerion.android.vault.keepass;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class KdbxStreamsTest {

	private static byte[] hashedBlocks(byte[]... blocks) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int index = 0;
		for (byte[] block : blocks) {
			out.write(KdbxBytes.i32le(index++));
			out.write(MessageDigest.getInstance("SHA-256").digest(block));
			out.write(KdbxBytes.i32le(block.length));
			out.write(block);
		}
		out.write(KdbxBytes.i32le(index));
		out.write(new byte[32]);
		out.write(KdbxBytes.i32le(0));
		return out.toByteArray();
	}

	private static byte[] drain(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[7];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}

	private static KdbxException.Reason reasonOf(InputStream in) {
		try {
			drain(in);
		} catch (IOException e) {
			return KdbxStreams.unwrap(e, KdbxException.Reason.WRONG_KEY,
					KdbxException.Reason.CORRUPTED).reason;
		}
		fail("stream accepted");
		throw new AssertionError();
	}

	@Test
	public void hashedBlocksAreConcatenated() throws Exception {
		byte[] a = "first block".getBytes(StandardCharsets.UTF_8);
		byte[] b = new byte[5000];
		new Random(1).nextBytes(b);
		byte[] joined = drain(new KdbxStreams.HashedBlockInputStream(
				new ByteArrayInputStream(hashedBlocks(a, b))));
		assertArrayEquals(KdbxBytes.concat(a, b), joined);
	}

	@Test
	public void corruptedHashedBlockIsRefused() throws Exception {
		byte[] a = "first block".getBytes(StandardCharsets.UTF_8);
		byte[] b = "second block".getBytes(StandardCharsets.UTF_8);
		byte[] stream = hashedBlocks(a, b);
		int secondData = 40 + a.length + 40;
		stream[secondData + 3] ^= 0x01;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(new KdbxStreams.HashedBlockInputStream(
						new ByteArrayInputStream(stream))));
	}

	@Test
	public void hashedBlockWithWrongIndexOrFinalHashIsRefused()
			throws Exception {
		byte[] a = "block".getBytes(StandardCharsets.UTF_8);
		byte[] stream = hashedBlocks(a);
		stream[0] = 5;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(new KdbxStreams.HashedBlockInputStream(
						new ByteArrayInputStream(stream))));
		byte[] badEnd = hashedBlocks(a);
		badEnd[40 + a.length + 4] = 1;
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(new KdbxStreams.HashedBlockInputStream(
						new ByteArrayInputStream(badEnd))));
		byte[] cut = Arrays.copyOf(hashedBlocks(a), 40 + a.length + 10);
		assertEquals(KdbxException.Reason.CORRUPTED,
				reasonOf(new KdbxStreams.HashedBlockInputStream(
						new ByteArrayInputStream(cut))));
	}

	@Test
	public void declarationScanCatchesSplitDoctype() throws Exception {
		byte[] xml = "<a><!DocType b></a>".getBytes(StandardCharsets.UTF_8);
		InputStream in = new KdbxStreams.DeclarationScanInputStream(
				new ByteArrayInputStream(xml));
		byte[] three = new byte[3];
		try {
			while (in.read(three) != -1) {
			}
			fail("accepted");
		} catch (IOException e) {
			assertEquals(KdbxException.Reason.CORRUPTED, KdbxStreams.unwrap(e,
					KdbxException.Reason.WRONG_KEY,
					KdbxException.Reason.CORRUPTED).reason);
		}
		byte[] clean = "<a>text with &lt;!ENTIT and DOCTYP words</a>"
				.getBytes(StandardCharsets.UTF_8);
		assertArrayEquals(clean, drain(new KdbxStreams.DeclarationScanInputStream(
				new ByteArrayInputStream(clean))));
	}

	@Test
	public void wipingBufferGrowsZeroesAndCaps() throws Exception {
		KdbxStreams.WipingBuffer buffer = new KdbxStreams.WipingBuffer(4, 10);
		byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
		buffer.write(data, 0, 3);
		buffer.write(data, 3, 9);
		assertEquals(10, buffer.size());
		assertArrayEquals(Arrays.copyOf(data, 10), buffer.toByteArray());
		buffer.wipe();
		assertEquals(0, buffer.size());
		assertArrayEquals(new byte[0], buffer.toByteArray());
	}

	@Test
	public void drainReadsToTheEnd() throws Exception {
		byte[] a = "block".getBytes(StandardCharsets.UTF_8);
		byte[] stream = KdbxBytes.concat(hashedBlocks(a), new byte[] {9});
		ByteArrayInputStream in = new ByteArrayInputStream(stream);
		KdbxStreams.HashedBlockInputStream blocks =
				new KdbxStreams.HashedBlockInputStream(in);
		KdbxStreams.drain(blocks);
		assertEquals(9, in.read());
		assertEquals(-1, in.read());
	}

	@Test
	public void limitedStreamStopsAtTheCap() throws Exception {
		byte[] data = new byte[100];
		assertArrayEquals(data, drain(new KdbxStreams.LimitedInputStream(
				new ByteArrayInputStream(data), 100)));
		assertEquals(KdbxException.Reason.TOO_LARGE,
				reasonOf(new KdbxStreams.LimitedInputStream(
						new ByteArrayInputStream(data), 99)));
	}

	@Test
	public void readAllGrowsAndEnforcesTheCap() throws Exception {
		byte[] data = new byte[150_000];
		new Random(2).nextBytes(data);
		assertArrayEquals(data, KdbxStreams.readAll(
				new ByteArrayInputStream(data), -1, 1_000_000));
		assertArrayEquals(data, KdbxStreams.readAll(
				new ByteArrayInputStream(data), data.length, 1_000_000));
		assertArrayEquals(data, KdbxStreams.readAll(
				new ByteArrayInputStream(data), data.length, data.length));
		try {
			KdbxStreams.readAll(new ByteArrayInputStream(data), -1,
					data.length - 1);
			fail("accepted");
		} catch (KdbxStreams.KdbxIOException e) {
			assertEquals(KdbxException.Reason.TOO_LARGE, e.reason.reason);
		}
		try {
			KdbxStreams.readAll(new ByteArrayInputStream(data), data.length,
					data.length - 1);
			fail("accepted");
		} catch (KdbxStreams.KdbxIOException e) {
			assertEquals(KdbxException.Reason.TOO_LARGE, e.reason.reason);
		}
		try {
			KdbxReader.readLimited(new ByteArrayInputStream(data), -1, 10);
			fail("accepted");
		} catch (KdbxException e) {
			assertEquals(KdbxException.Reason.TOO_LARGE, e.reason);
		}
	}
}
