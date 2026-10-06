package com.professor.zerion.android.util;

import android.content.Context;
import android.content.res.XmlResourceParser;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class FileProviderStagingBehaviourTest {

	private static final String[] RETIRED =
			{"channel_attach_view", "media_docs", "grouptr_view"};

	@Test
	public void theProviderServesOnlyTheHandoffDirectoryForReceivedContent()
			throws Exception {
		Set<String> cacheRoots = new HashSet<>();
		Set<String> allRoots = new HashSet<>();
		XmlResourceParser p = ApplicationProvider.getApplicationContext()
				.getResources().getXml(R.xml.file_paths);
		for (int e = p.next(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
			if (e != XmlPullParser.START_TAG || "paths".equals(p.getName())) {
				continue;
			}
			String path = p.getAttributeValue(null, "path");
			allRoots.add(p.getName() + ":" + path);
			if ("cache-path".equals(p.getName())) cacheRoots.add(path);
		}
		assertTrue("the hand-off directory is served",
				cacheRoots.contains(ExternalHandoff.DIR + "/"));
		for (String old : RETIRED) {
			for (String root : allRoots) {
				assertFalse(old + " is still served: " + root,
						root.contains(old));
			}
		}
		assertEquals(new HashSet<>(Arrays.asList("camera_photos/",
				"vault_share/", "zenc_share/", ExternalHandoff.DIR + "/")),
				cacheRoots);
	}

	@Test
	public void theSweepEmptiesTheHandoffAndTheRetiredDirectories()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		File cache = ctx.getCacheDir();
		Set<File> planted = new HashSet<>();
		for (String dir : new String[] {ExternalHandoff.DIR, RETIRED[0],
				RETIRED[1], RETIRED[2]}) {
			File d = new File(cache, dir);
			assertTrue(d.mkdirs() || d.isDirectory());
			File f = new File(d, "leftover.bin");
			Files.write(f.toPath(), "left".getBytes(StandardCharsets.UTF_8));
			planted.add(f);
		}
		File unrelated = new File(cache, "unrelated-" + System.nanoTime());
		Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));

		CacheSweeper.sweep(ctx);

		for (File f : planted) {
			assertFalse(f + " survived the sweep", f.exists());
		}
		assertTrue("a file the sweep does not own was removed",
				unrelated.exists());
		assertTrue(unrelated.delete());
	}
}
