package com.professor.zerion.android.vault.share;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.MimeTypeMap;

import com.professor.zerion.android.util.CacheSweeper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultShareRegistryTest {

	private final Context ctx = RuntimeEnvironment.getApplication();

	@Before
	public void setUp() {
		Shadows.shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("txt", "text/plain");
	}

	@After
	public void tearDown() {
		VaultShareRegistry.releaseAll();
	}

	private static byte[] item() {
		return "synthetic vault item".getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void aRegisteredItemIsReadableUnderItsOwnUri() {
		byte[] data = item();
		Uri uri = VaultShareRegistry.register(ctx, data, "note.txt");
		assertEquals(ctx.getPackageName() + ".vaultshare", uri.getAuthority());
		assertTrue(VaultShareRegistry.isVaultShare(ctx, uri));
		VaultShareRegistry.Entry e = VaultShareRegistry.get(uri);
		assertNotNull(e);
		assertEquals("note.txt", e.name);
		assertEquals("text/plain", e.mimeType);
		byte[] buf = new byte[64];
		int n = VaultShareRegistry.read(e, 0, buf);
		assertArrayEquals(item(), Arrays.copyOf(buf, n));
	}

	@Test
	public void twoHandoffsGetDifferentUnguessableUris() {
		Uri a = VaultShareRegistry.register(ctx, item(), "x");
		Uri b = VaultShareRegistry.register(ctx, item(), "x");
		assertFalse(a.equals(b));
		assertEquals(32, a.getPathSegments().get(1).length());
	}

	@Test
	public void releasingOverwritesTheBytesAndTheUriStopsResolving() {
		byte[] data = item();
		Uri uri = VaultShareRegistry.register(ctx, data, "x");
		VaultShareRegistry.Entry e = VaultShareRegistry.get(uri);
		VaultShareRegistry.release(uri);
		assertNull(VaultShareRegistry.get(uri));
		assertArrayEquals(new byte[data.length], data);
		assertEquals("a read after the release fails instead of returning "
				+ "the overwritten bytes", -1,
				VaultShareRegistry.read(e, 0, new byte[8]));
	}

	@Test
	public void theAppLockAndSignOutSweepReleasesEveryItem() {
		Uri a = VaultShareRegistry.register(ctx, item(), "a");
		CacheSweeper.sweep(ctx);
		assertNull(VaultShareRegistry.get(a));
	}

	@Test
	public void anUnreadItemExpiresAfterTheMaximumLifetime() {
		byte[] data = item();
		Uri uri = VaultShareRegistry.register(ctx, data, "x");
		SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis()
				+ VaultShareRegistry.MAX_LIFETIME_MS - 1000L);
		assertNotNull("still held just before the limit",
				VaultShareRegistry.get(uri));
		SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + 2000L);
		assertNull(VaultShareRegistry.get(uri));
		assertArrayEquals(new byte[data.length], data);
	}

	@Test
	public void aForeignOrMalformedUriResolvesToNothing() {
		VaultShareRegistry.register(ctx, item(), "x");
		assertNull(VaultShareRegistry.get(Uri.parse(
				"content://" + ctx.getPackageName() + ".vaultshare/item")));
		assertNull(VaultShareRegistry.get(Uri.parse(
				"content://" + ctx.getPackageName()
						+ ".vaultshare/other/0123/x")));
		assertNull(VaultShareRegistry.get(Uri.parse(
				"content://" + ctx.getPackageName()
						+ ".vaultshare/item/00000000000000000000000000000000/x")));
	}
}
