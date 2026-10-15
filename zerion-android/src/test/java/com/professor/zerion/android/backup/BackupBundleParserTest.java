package com.professor.zerion.android.backup;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BackupBundleParserTest {

	static final String EARLIER_BUNDLE = "00000001000d4f6c64204261636b7570"
			+ "20c3a900000020a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7"
			+ "b8b9babbbcbdbebf0000003c53514c69746520666f726d61742033002061"
			+ "6e206f6c64206261636b7570206d616465206265666f7265207468652070"
			+ "6172736572206368616e676500000000";

	private static final int HALF_GIB = 512 * 1024 * 1024;
	private static final long SMALL = 1024 * 1024;

	static byte[] hex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
		}
		return out;
	}

	private static byte[] raw(String name, int keyLength, byte[] key,
			int dbLength, byte[] db, int vaultLength, byte[] vault)
			throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(BackupBundle.BUNDLE_VERSION);
		out.writeUTF(name);
		out.writeInt(keyLength);
		out.write(key);
		out.writeInt(dbLength);
		out.write(db);
		out.writeInt(vaultLength);
		out.write(vault);
		return bytes.toByteArray();
	}

	private static byte[] valid() throws IOException {
		return raw("Alice", 32, new byte[32], 5, new byte[] {1, 2, 3, 4, 5},
				0, new byte[0]);
	}

	private static void assertCorruptWithoutLargeAllocation(byte[] input) {
		ThreadAllocations allocations = ThreadAllocations.require();
		long before = allocations.now();
		try {
			BackupBundle.fromBytes(input);
			fail("expected CORRUPT");
		} catch (BackupException e) {
			assertEquals(CORRUPT, e.reason);
		}
		long used = allocations.now() - before;
		assertTrue("parsing " + input.length + " bytes allocated " + used,
				used < SMALL);
	}

	@Test
	public void tenBytesClaimingHalfAGibibyteAreCorruptWithoutAllocatingIt()
			throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(1);
		out.writeUTF("");
		out.writeInt(HALF_GIB);
		byte[] input = bytes.toByteArray();
		assertEquals(10, input.length);
		assertCorruptWithoutLargeAllocation(input);
	}

	@Test
	public void negativeBlockLengthsAreCorrupt() throws IOException {
		byte[] key = new byte[32];
		assertCorruptWithoutLargeAllocation(
				raw("A", -1, new byte[0], 0, new byte[0], 0, new byte[0]));
		assertCorruptWithoutLargeAllocation(
				raw("A", 32, key, -1, new byte[0], 0, new byte[0]));
		assertCorruptWithoutLargeAllocation(
				raw("A", 32, key, 0, new byte[0], Integer.MIN_VALUE,
						new byte[0]));
	}

	@Test
	public void lengthsBeyondTheInputOrTheLimitAreCorrupt()
			throws IOException {
		byte[] key = new byte[32];
		for (int len : new int[] {6, HALF_GIB, HALF_GIB + 1,
				Integer.MAX_VALUE}) {
			assertCorruptWithoutLargeAllocation(
					raw("A", 32, key, len, new byte[5], 0, new byte[0]));
			assertCorruptWithoutLargeAllocation(
					raw("A", 32, key, 0, new byte[0], len, new byte[5]));
		}
	}

	@Test
	public void aNameLongerThanTheInputIsCorrupt() {
		byte[] input = {0, 0, 0, 1, (byte) 0xFF, (byte) 0xFF, 'a', 'b'};
		assertCorruptWithoutLargeAllocation(input);
	}

	@Test
	public void everyTruncationIsCorrupt() throws IOException {
		byte[] full = valid();
		for (int len = 0; len < full.length; len++) {
			assertCorruptWithoutLargeAllocation(Arrays.copyOf(full, len));
		}
	}

	@Test
	public void trailingDataIsCorrupt() throws IOException {
		byte[] full = valid();
		assertCorruptWithoutLargeAllocation(Arrays.copyOf(full, full.length + 1));
		byte[] twice = Arrays.copyOf(full, full.length * 2);
		System.arraycopy(full, 0, twice, full.length, full.length);
		assertCorruptWithoutLargeAllocation(twice);
	}

	@Test
	public void aDatabaseKeyOfAnyOtherSizeIsCorrupt() throws IOException {
		for (int size : new int[] {0, 1, 16, 31, 33, 64}) {
			assertCorruptWithoutLargeAllocation(raw("A", size, new byte[size],
					5, new byte[5], 0, new byte[0]));
		}
	}

	@Test
	public void aBundleOfAnyOtherKeySizeIsNotWritten() {
		try {
			new BackupBundle("A", new byte[16], new byte[5], null).toBytes();
			fail("a bundle that cannot be read back must not be written");
		} catch (RuntimeException expected) {
		}
	}

	@Test
	public void aRealBundleRoundTrips() throws BackupException {
		byte[] key = new byte[32];
		for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7);
		byte[] db = new byte[100_000];
		new java.util.Random(5).nextBytes(db);
		byte[] vault = {9, 8, 7};
		BackupBundle bundle = new BackupBundle("Zoë 😀", key.clone(),
				db.clone(), vault.clone());
		byte[] bytes = bundle.toBytes();
		assertArrayEquals("the layout is unchanged", legacyLayout(bundle),
				bytes);
		BackupBundle back = BackupBundle.fromBytes(bytes);
		assertEquals("Zoë 😀", back.displayName);
		assertArrayEquals(key, back.dbKey);
		assertArrayEquals(db, back.dbFile);
		assertArrayEquals(vault, back.vault);
	}

	@Test
	public void aBundleWrittenByTheEarlierCodeStillParses()
			throws BackupException {
		BackupBundle back = BackupBundle.fromBytes(hex(EARLIER_BUNDLE));
		assertEquals("Old Backup é", back.displayName);
		byte[] key = new byte[32];
		for (int i = 0; i < 32; i++) key[i] = (byte) (0xA0 + i);
		assertArrayEquals(key, back.dbKey);
		assertEquals("SQLite format 3\u0000 an old backup made before the parser change",
				new String(back.dbFile, StandardCharsets.UTF_8));
		assertNull(back.vault);
	}

	private static byte[] legacyLayout(BackupBundle b) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeInt(1);
			out.writeUTF(b.displayName);
			out.writeInt(b.dbKey.length);
			out.write(b.dbKey);
			out.writeInt(b.dbFile.length);
			out.write(b.dbFile);
			byte[] vault = b.vault == null ? new byte[0] : b.vault;
			out.writeInt(vault.length);
			out.write(vault);
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
