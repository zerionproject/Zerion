package com.professor.zerion.android.vault.share;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;

import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.vault.VaultManager;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class VaultShareLifecycleDeviceTest {

	private final SecureRandom random = new SecureRandom();
	private Context context;
	private ContentResolver resolver;
	private final StringBuilder report = new StringBuilder();

	@Before
	public void setUp() {
		context = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		resolver = context.getContentResolver();
	}

	@After
	public void tearDown() throws IOException {
		if (report.length() == 0) return;
		File dir = new File(context.getFilesDir(), "device-test-output");
		if (!dir.isDirectory() && !dir.mkdirs()) return;
		try (FileOutputStream out = new FileOutputStream(
				new File(dir, "f07-lifecycle.tsv"), true)) {
			out.write(report.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private void line(String s) {
		report.append(Build.MODEL).append('\t').append(s).append('\n');
	}

	private String newMarker() {
		byte[] b = new byte[12];
		random.nextBytes(b);
		StringBuilder sb = new StringBuilder("ZTF07MARKER");
		for (byte x : b) sb.append(String.format("%02x", x & 0xff));
		return sb.toString();
	}

	private static byte[] itemWith(String marker, int size) {
		byte[] m = marker.getBytes(StandardCharsets.US_ASCII);
		byte[] data = new byte[Math.max(size, m.length * 2)];
		for (int i = 0; i < data.length; i++) data[i] = (byte) ('a' + i % 26);
		System.arraycopy(m, 0, data, 0, m.length);
		System.arraycopy(m, 0, data, data.length - m.length, m.length);
		return data;
	}

	private byte[] readAll(Uri uri) throws IOException {
		try (InputStream in = resolver.openInputStream(uri)) {
			if (in == null) throw new FileNotFoundException();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	private void assertGone(Uri uri, byte[] data) {
		try {
			readAll(uri);
			fail("a released item must not open");
		} catch (IOException expected) {
		}
		assertNull(VaultShareRegistry.get(uri));
		assertArrayEquals("the bytes are overwritten", new byte[data.length],
				data);
	}

	private List<String> residue(String marker) throws IOException {
		File dataDir = context.getFilesDir().getParentFile();
		List<String> hits = new ArrayList<>();
		scan(dataDir, marker.getBytes(StandardCharsets.US_ASCII), hits);
		return hits;
	}

	private static void scan(File f, byte[] needle, List<String> hits)
			throws IOException {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children == null) return;
			for (File c : children) {
				if (c.getName().equals("device-test-output")) continue;
				scan(c, needle, hits);
			}
			return;
		}
		if (!f.isFile() || !f.canRead()) return;
		byte[] window = new byte[1 << 16];
		int carry = 0;
		try (InputStream in = new FileInputStream(f)) {
			int n;
			while ((n = in.read(window, carry, window.length - carry)) > 0) {
				int len = carry + n;
				if (indexOf(window, len, needle) >= 0) {
					hits.add(f.getAbsolutePath());
					return;
				}
				carry = Math.min(needle.length - 1, len);
				System.arraycopy(window, len - carry, window, 0, carry);
			}
		}
	}

	private static int indexOf(byte[] hay, int len, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= len; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	@Test
	public void handoffConsumptionAndReleaseLeaveNothingBehind()
			throws Exception {
		String marker = newMarker();
		byte[] data = itemWith(marker, 300_000);
		byte[] copy = data.clone();
		Uri uri = VaultShareRegistry.register(context, data, "photo.jpg");
		assertEquals("image/jpeg", resolver.getType(uri));
		try (Cursor c = resolver.query(uri, null, null, null, null)) {
			assertTrue(c != null && c.moveToFirst());
			assertEquals("photo.jpg", c.getString(
					c.getColumnIndex(OpenableColumns.DISPLAY_NAME)));
			assertEquals(copy.length, c.getLong(
					c.getColumnIndex(OpenableColumns.SIZE)));
		}
		assertArrayEquals("first read, as the header pass does", copy,
				readAll(uri));
		assertArrayEquals("second read, as the content pass does", copy,
				readAll(uri));
		assertEquals("no file holds the item while it is shared",
				new ArrayList<String>(), residue(marker));
		VaultShareRegistry.release(uri);
		assertGone(uri, data);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("handoff_consumption_release\tok\tbytes\t" + copy.length
				+ "\tresidue_files\t0");
	}

	@Test
	public void aCancelledPickerReleasesTheItem() throws Exception {
		String marker = newMarker();
		byte[] data = itemWith(marker, 50_000);
		Uri uri = VaultShareRegistry.register(context, data, "doc.pdf");
		VaultShareRegistry.release(uri);
		assertGone(uri, data);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("cancel\tok\tresidue_files\t0");
	}

	@Test
	public void aReleaseDuringAReadFailsTheReaderInsteadOfReturningZeros()
			throws Exception {
		String marker = newMarker();
		byte[] data = itemWith(marker, 16 * 1024 * 1024);
		byte[] copy = data.clone();
		Uri uri = VaultShareRegistry.register(context, data, "video.mp4");
		ByteArrayOutputStream got = new ByteArrayOutputStream();
		boolean failed = false;
		try (InputStream in = resolver.openInputStream(uri)) {
			byte[] buf = new byte[8192];
			int n = in.read(buf);
			got.write(buf, 0, n);
			VaultShareRegistry.release(uri);
			while ((n = in.read(buf)) >= 0) got.write(buf, 0, n);
		} catch (IOException e) {
			failed = true;
		}
		byte[] read = got.toByteArray();
		assertTrue("the reader either failed or got a strict prefix of the "
						+ "item, never overwritten bytes as content",
				failed || read.length < copy.length);
		assertArrayEquals(Arrays.copyOf(copy, read.length), read);
		assertGone(uri, data);
		line("error_release_during_read\tok\treader_failed\t" + failed
				+ "\tbytes_before_failure\t" + read.length);
	}

	@Test
	public void lockingTheVaultReleasesEveryItem() throws Exception {
		String marker = newMarker();
		byte[] a = itemWith(marker, 10_000), b = itemWith(marker, 20_000);
		Uri ua = VaultShareRegistry.register(context, a, "a.txt");
		Uri ub = VaultShareRegistry.register(context, b, "b.txt");
		new VaultManager(context).lockVault();
		assertGone(ua, a);
		assertGone(ub, b);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("vault_lock\tok\tresidue_files\t0");
	}

	@Test
	public void theAppLockSweepReleasesEveryItem() throws Exception {
		String marker = newMarker();
		byte[] a = itemWith(marker, 10_000);
		Uri ua = VaultShareRegistry.register(context, a, "a.txt");
		CacheSweeper.sweep(context);
		assertGone(ua, a);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("app_lock_sweep\tok\tresidue_files\t0");
	}

	@Test
	public void anUnreadItemIsReleasedAtTheMaximumLifetime() throws Exception {
		Bundle args = InstrumentationRegistry.getArguments();
		Assume.assumeTrue("run with -e expiry true",
				"true".equals(args.getString("expiry")));
		String marker = newMarker();
		byte[] data = itemWith(marker, 10_000);
		Uri uri = VaultShareRegistry.register(context, data, "late.txt");
		Thread.sleep(VaultShareRegistry.MAX_LIFETIME_MS - 5_000L);
		assertTrue("held until the limit", VaultShareRegistry.get(uri) != null);
		Thread.sleep(10_000L);
		assertGone(uri, data);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("expiry\tok\tlifetime_ms\t" + VaultShareRegistry.MAX_LIFETIME_MS);
	}

	@Test
	public void processDeathPhaseOneLeavesAnItemInMemory() throws Exception {
		Bundle args = InstrumentationRegistry.getArguments();
		Assume.assumeTrue("process-death phase 1",
				"1".equals(args.getString("phase")));
		String marker = args.getString("marker");
		byte[] data = itemWith(marker, 200_000);
		byte[] copy = data.clone();
		Uri uri = VaultShareRegistry.register(context, data, "held.jpg");
		assertArrayEquals(copy, readAll(uri));
		assertEquals(new ArrayList<String>(), residue(marker));
		line("process_death_phase1\theld_in_memory\tpid\t"
				+ android.os.Process.myPid());
	}

	@Test
	public void processDeathPhaseTwoFindsNothing() throws Exception {
		Bundle args = InstrumentationRegistry.getArguments();
		Assume.assumeTrue("process-death phase 2",
				"2".equals(args.getString("phase")));
		String marker = args.getString("marker");
		assertEquals("a new process holds no shared item", 0,
				VaultShareRegistry.size());
		File legacy = new File(context.getCacheDir(), "vault_share");
		String[] left = legacy.list();
		assertTrue("no share directory content", left == null
				|| left.length == 0);
		assertEquals(new ArrayList<String>(), residue(marker));
		line("process_death_phase2\tnothing_held\tpid\t"
				+ android.os.Process.myPid() + "\tresidue_files\t0");
	}
}
