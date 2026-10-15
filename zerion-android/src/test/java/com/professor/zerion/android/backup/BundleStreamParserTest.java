package com.professor.zerion.android.backup;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static com.professor.zerion.android.backup.BackupException.Reason.CORRUPT;
import static com.professor.zerion.android.backup.BackupException.Reason.UNSUPPORTED_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BundleStreamParserTest {

	private static byte[] bundle(int version, String name, int keyLength,
			byte[] db, int vaultLength) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeInt(version);
		out.writeUTF(name);
		out.writeInt(keyLength);
		byte[] key = new byte[Math.max(0, keyLength)];
		Arrays.fill(key, (byte) 7);
		out.write(key);
		out.writeInt(db.length);
		out.write(db);
		out.writeInt(vaultLength);
		out.write(new byte[Math.max(0, vaultLength)]);
		return bytes.toByteArray();
	}

	private static byte[] db(int size) {
		byte[] b = new byte[size];
		new Random(size).nextBytes(b);
		return b;
	}

	private static void feed(BundleStreamParser parser, byte[] bytes, int step)
			throws IOException {
		for (int off = 0; off < bytes.length; off += step) {
			parser.write(bytes, off, Math.min(step, bytes.length - off));
		}
	}

	@Test
	public void aBundleArrivingInAnyPiecesIsParsed() throws Exception {
		byte[] db = db(10_000);
		byte[] bytes = bundle(1, "Zoë 😀", 32, db, 0);
		for (int step : new int[] {1, 3, 7, 4096, bytes.length}) {
			ByteArrayOutputStream file = new ByteArrayOutputStream();
			BundleStreamParser parser = new BundleStreamParser(file);
			feed(parser, bytes, step);
			parser.finish();
			assertEquals("Zoë 😀", parser.displayName());
			byte[] key = new byte[32];
			Arrays.fill(key, (byte) 7);
			assertArrayEquals(key, parser.dbKey());
			assertArrayEquals(db, file.toByteArray());
		}
	}

	@Test
	public void aStreamedBundleMatchesTheExportPrefix() throws Exception {
		byte[] key = new byte[32];
		Arrays.fill(key, (byte) 7);
		byte[] db = db(777);
		byte[] prefix = BackupBundle.prefix("Name", key, db.length);
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		BundleStreamParser parser = new BundleStreamParser(file);
		parser.write(prefix);
		parser.write(db);
		parser.write(BackupBundle.suffix());
		parser.finish();
		assertEquals("Name", parser.displayName());
		assertArrayEquals(db, file.toByteArray());
		assertArrayEquals(bundle(1, "Name", 32, db, 0),
				new BackupBundle("Name", key, db, null).toBytes());
	}

	private static void assertRefused(byte[] bytes,
			BackupException.Reason reason) {
		BundleStreamParser parser =
				new BundleStreamParser(new ByteArrayOutputStream());
		try {
			parser.write(bytes);
			parser.finish();
			fail("accepted");
		} catch (BundleStreamParser.Malformed e) {
			assertEquals(reason, e.reason);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void malformedBundlesAreRefused() throws Exception {
		byte[] good = bundle(1, "Name", 32, db(100), 0);
		assertRefused(bundle(2, "Name", 32, db(100), 0), UNSUPPORTED_VERSION);
		assertRefused(bundle(1, "Name", 31, db(100), 0), CORRUPT);
		assertRefused(bundle(1, "Name", 33, db(100), 0), CORRUPT);
		assertRefused(bundle(1, "Name", 32, db(100), 5), CORRUPT);
		assertRefused(Arrays.copyOf(good, good.length + 1), CORRUPT);
		for (int cut = 0; cut < good.length; cut++) {
			assertRefused(Arrays.copyOf(good, cut), CORRUPT);
		}
		byte[] negative = good.clone();
		int dbLengthAt = 4 + 2 + 4 + 4 + 32;
		negative[dbLengthAt] = (byte) 0x80;
		assertRefused(negative, CORRUPT);
		byte[] badName = bundle(1, "ab", 32, db(10), 0);
		badName[6] = (byte) 0xC0;
		badName[7] = (byte) 0x41;
		assertRefused(badName, CORRUPT);
	}
}
