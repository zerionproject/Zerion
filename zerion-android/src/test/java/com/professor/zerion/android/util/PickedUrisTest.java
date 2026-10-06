package com.professor.zerion.android.util;

import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PickedUrisTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void pickersMayOnlyReturnAnotherAppsContent() {
		String own = ctx.getPackageName();
		assertFalse(PickedUris.isForeignContent(ctx,
				Uri.parse("file:///data/data/" + own + "/files/x")));
		assertFalse(PickedUris.isForeignContent(ctx,
				Uri.parse("content://" + own + ".fileprovider/cache/x")));
		assertFalse(PickedUris.isForeignContent(ctx,
				Uri.parse("content://" + own + ".vaultshare/s/abc")));
		assertFalse(PickedUris.isForeignContent(ctx,
				Uri.parse("content://10@" + own + ".fileprovider/files/x")));
		assertFalse(PickedUris.isForeignContent(ctx,
				Uri.parse("http://example.org/x")));
		assertTrue(PickedUris.isForeignContent(ctx, Uri.parse(
				"content://com.android.providers.downloads.documents/"
						+ "document/1")));
		assertTrue(PickedUris.isForeignContent(ctx,
				Uri.parse("content://media/external/images/media/7")));
	}
}
