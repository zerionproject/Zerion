package com.professor.zerion.android.vault.ui;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class NoClearnetFetchTest {

	private final Application app = ApplicationProvider.getApplicationContext();

	@Test
	public void theEmojiLibraryIsNotStartedAtLaunch() throws Exception {
		ProviderInfo startup = app.getPackageManager().getProviderInfo(
				new ComponentName(app,
						"androidx.startup.InitializationProvider"),
				PackageManager.GET_META_DATA);
		Bundle meta = startup.metaData;
		assertFalse("the emoji library starts at launch", meta != null
				&& meta.containsKey("androidx.emoji2.text.EmojiCompatInitializer"));
	}

	@Test
	public void theWebViewSendsNoMetricsOrSafeBrowsingLookups()
			throws Exception {
		ApplicationInfo info = app.getPackageManager().getApplicationInfo(
				app.getPackageName(), PackageManager.GET_META_DATA);
		assertNotNull(info.metaData);
		assertTrue(info.metaData.getBoolean(
				"android.webkit.WebView.MetricsOptOut", false));
		assertFalse(info.metaData.getBoolean(
				"android.webkit.WebView.EnableSafeBrowsing", true));
	}

}
