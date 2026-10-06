package com.professor.zerion.android.util;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WebEngineLeftoversTest {

	private static File touch(File dir, String name) throws IOException {
		dir.mkdirs();
		File f = new File(dir, name);
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(new byte[] {1, 2, 3});
		}
		return f;
	}

	@Test
	public void leftoverWebEngineDataIsRemoved() throws Exception {
		Context ctx = RuntimeEnvironment.getApplication();
		File data = new File(ctx.getApplicationInfo().dataDir);
		File webview = new File(data, "app_webview");
		File cookies = touch(new File(webview, "Default"), "Cookies");
		File textures = touch(new File(data, "app_textures"), "t");
		File cache = touch(new File(ctx.getCacheDir(), "WebView"), "c");

		CacheSweeper.sweep(ctx);

		assertFalse(cookies.exists());
		assertFalse(webview.exists());
		assertFalse(textures.exists());
		assertFalse(cache.exists());
	}
}
