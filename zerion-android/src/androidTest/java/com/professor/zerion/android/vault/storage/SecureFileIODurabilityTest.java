package com.professor.zerion.android.vault.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * On-device durability tests for the shared vault write primitive. They run
 * against an isolated temporary vault directory, never the real vault, and prove
 * the behaviour the spend journal will depend on: a write into a freshly created
 * nested directory completes and is readable, it survives a fresh reader, the
 * directory fsync actually works on this device's filesystem, and a directory
 * fsync failure propagates so a write is never reported as durable when its
 * directory entry is not. Physical power loss cannot be simulated here; these
 * prove the ordering and that the fsync calls succeed or fail loudly.
 */
@RunWith(AndroidJUnit4.class)
public class SecureFileIODurabilityTest {

	private Context ctx;
	private File base;
	private SecureFileIO io;

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		base = new File(ctx.getCacheDir(), "sfio-" + System.nanoTime());
		assertTrue(base.mkdirs());
		io = new SecureFileIO(ctx, new File(base, "vault"));
	}

	@After
	public void tearDown() {
		deleteTree(base);
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new SecureRandom().nextBytes(b);
		return b;
	}

	@Test
	public void writeIntoNewNestedDirectoryIsDurableAndReadable()
			throws Exception {
		io.createDirectory("items/abc");
		byte[] data = randomBytes(5000);
		io.writeSecure("items/abc/content.bin", data);
		assertArrayEquals(data, io.readSecure("items/abc/content.bin"));
		File itemDir = new File(base, "vault/items/abc");
		String[] names = itemDir.list();
		assertTrue(names != null);
		for (String n : names) {
			assertTrue("no temp file may survive a completed write, saw " + n,
					n.indexOf(".tmp") < 0);
		}
	}

	@Test
	public void committedWriteSurvivesAFreshReader() throws Exception {
		io.createDirectory("items/xyz");
		byte[] data = randomBytes(9000);
		io.writeSecure("items/xyz/content.bin", data);
		SecureFileIO reopened = new SecureFileIO(ctx, new File(base, "vault"));
		assertArrayEquals("a durably committed item is readable by a new reader",
				data, reopened.readSecure("items/xyz/content.bin"));
	}

	@Test
	public void directoryFsyncWorksOnThisDeviceForADeepItemDirectory()
			throws Exception {
		io.createDirectory("items/deep/more");
		byte[] data = randomBytes(2048);
		io.writeSecure("items/deep/more/f.bin", data);
		assertArrayEquals(data, io.readSecure("items/deep/more/f.bin"));
	}

	@Test
	public void directoryFsyncFailureFailsTheWrite() throws Exception {
		io.createDirectory("items/f1");
		SecureFileIO failing = new SecureFileIO(ctx, new File(base, "vault")) {
			@Override
			void fsyncDir(File dir) throws IOException {
				throw new IOException("injected directory fsync failure");
			}
		};
		try {
			failing.writeSecure("items/f1/c.bin", randomBytes(1000));
			fail("a directory fsync failure must fail the write");
		} catch (IOException expected) {
		}
	}

	@Test
	public void directoryFsyncFailureFailsDirectoryCreation() {
		SecureFileIO failing = new SecureFileIO(ctx, new File(base, "vault")) {
			@Override
			void fsyncDir(File dir) throws IOException {
				throw new IOException("injected directory fsync failure");
			}
		};
		try {
			failing.createDirectory("items/newdir");
			fail("a directory fsync failure must fail directory creation");
		} catch (IOException expected) {
		}
	}

	private static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) for (File k : kids) deleteTree(k);
		f.delete();
	}
}
