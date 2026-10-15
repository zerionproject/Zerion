package com.professor.zerion.android.backup;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import static com.professor.zerion.android.backup.BackupException.Reason.TOO_LARGE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupMemoryTest {

	private static final long MIB = 1024 * 1024;

	private static byte[] random(int size) {
		byte[] b = new byte[size];
		new Random(size).nextBytes(b);
		return b;
	}

	private static InputStream unreadable() {
		return new InputStream() {
			@Override
			public int read() throws IOException {
				throw new AssertionError("the file was read");
			}
		};
	}

	@Test
	public void theLimitLeavesAQuarterOfTheHeapAndCountsEveryCopy() {
		assertEquals(128 * MIB, BackupMemory.limit(3, 512 * MIB));
		assertEquals(64 * MIB, BackupMemory.limit(3, 256 * MIB));
		assertEquals(BackupMemory.MAX_FILE_BYTES,
				BackupMemory.limit(3, 8192 * MIB));
	}

	@Test
	public void aBackupTooLargeForTheHeapIsRefusedWithItsOwnReason()
			throws BackupException {
		BackupMemory.check(64 * MIB, 256 * MIB);
		try {
			BackupMemory.check(64 * MIB + 1, 256 * MIB);
			fail("expected TOO_LARGE");
		} catch (BackupException e) {
			assertEquals(TOO_LARGE, e.reason);
		}
	}

	@Test
	public void aDeclaredSizeAboveTheLimitIsRefusedBeforeReading()
			throws IOException {
		try {
			BackupMemory.read(unreadable(), 1001, 1000);
			fail("expected TOO_LARGE");
		} catch (BackupException e) {
			assertEquals(TOO_LARGE, e.reason);
		}
	}

	@Test
	public void aKnownSizeIsReadIntoOneExactArray() throws Exception {
		ThreadAllocations allocations = ThreadAllocations.require();
		int size = (int) (8 * MIB);
		byte[] data = random(size);
		InputStream in = new ByteArrayInputStream(data);
		long before = allocations.now();
		byte[] read = BackupMemory.read(in, size, 16 * MIB);
		long used = allocations.now() - before;
		assertArrayEquals(data, read);
		assertTrue("reading " + size + " bytes allocated " + used,
				used < size + MIB);
	}

	@Test
	public void anUnknownSizeWithinTheLimitIsReadExactly() throws Exception {
		byte[] data = random(150_000);
		byte[] read = BackupMemory.read(new ByteArrayInputStream(data), -1,
				MIB);
		assertArrayEquals(data, read);
		byte[] exact = random(65_536);
		assertArrayEquals(exact, BackupMemory.read(
				new ByteArrayInputStream(exact), -1, MIB));
		assertEquals(0, BackupMemory.read(
				new ByteArrayInputStream(new byte[0]), -1, MIB).length);
	}

	@Test
	public void anUnknownSizeOverTheLimitIsRefused() throws Exception {
		try {
			BackupMemory.read(new ByteArrayInputStream(random(200_000)), -1,
					100_000);
			fail("expected TOO_LARGE");
		} catch (BackupException e) {
			assertEquals(TOO_LARGE, e.reason);
		}
		try {
			BackupMemory.read(new ByteArrayInputStream(random(100_001)), -1,
					100_000);
			fail("expected TOO_LARGE");
		} catch (BackupException e) {
			assertEquals(TOO_LARGE, e.reason);
		}
		assertEquals(100_000, BackupMemory.read(
				new ByteArrayInputStream(random(100_000)), -1, 100_000).length);
	}

	@Test
	public void aFileThatDoesNotMatchItsDeclaredSizeIsAnError()
			throws BackupException {
		try {
			BackupMemory.read(new ByteArrayInputStream(random(50)), 100, MIB);
			fail("a short file must not be accepted");
		} catch (IOException expected) {
		}
		try {
			BackupMemory.read(new ByteArrayInputStream(random(101)), 100, MIB);
			fail("a file that grew must not be accepted");
		} catch (IOException expected) {
		}
	}
}
