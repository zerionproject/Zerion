package com.professor.zerion.android.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class ExternalHandoffDeviceTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@After
	public void tearDown() {
		ExternalHandoff.end(ctx);
	}

	private Uri uriFor(File f) {
		return FileProvider.getUriForFile(ctx,
				ctx.getPackageName() + ".fileprovider", f);
	}

	private byte[] read(Uri uri) throws IOException {
		try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
			if (in == null) throw new FileNotFoundException();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	@Test
	public void theCopyIsServedWhileTheHandOffLastsAndGoneAfter()
			throws Exception {
		byte[] content = "%PDF-1.7 handed over".getBytes(
				StandardCharsets.US_ASCII);
		File copy = ExternalHandoff.stage(ctx, content, "application/pdf");
		Uri uri = uriFor(copy);
		assertTrue(uri.getPath(), uri.getPath().startsWith(
				"/" + ExternalHandoff.DIR + "/"));
		assertTrue(copy.getName().matches("[0-9a-f]{32}\\.pdf"));
		assertArrayEquals(content, read(uri));
		ExternalHandoff.end(ctx);
		long deadline = System.currentTimeMillis() + 5000;
		while (copy.exists() && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertFalse(copy.exists());
		try {
			read(uri);
			fail("the copy is still served after the hand-off");
		} catch (FileNotFoundException | SecurityException expected) {
		}
	}

	@Test
	public void directoriesEarlierVersionsUsedAreNotServed() throws Exception {
		for (String old : new String[] {"channel_attach_view", "media_docs",
				"grouptr_view"}) {
			File dir = new File(ctx.getCacheDir(), old);
			assertTrue(dir.mkdirs() || dir.isDirectory());
			File f = new File(dir, "att_0123456789abcdef.pdf");
			try {
				uriFor(f);
				fail(old + " is still served");
			} catch (IllegalArgumentException expected) {
			} finally {
				f.delete();
				dir.delete();
			}
		}
	}
}
