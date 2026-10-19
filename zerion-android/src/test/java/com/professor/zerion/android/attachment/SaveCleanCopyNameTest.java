package com.professor.zerion.android.attachment;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SaveCleanCopyNameTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		SaveCleanCopy.clearAll();
	}

	@Test
	public void theSaveScreenSuggestsTheKindAndTheMomentAsTheName()
			throws Exception {
		Context app = RuntimeEnvironment.getApplication();
		String token = SaveCleanCopy.offer(
				"Route for Saturday".getBytes(StandardCharsets.UTF_8),
				"text/plain");
		Intent start = new Intent(app, SaveCleanCopyActivity.class)
				.putExtra(SaveCleanCopy.EXTRA_TOKEN, token);
		SaveCleanCopyActivity screen = Robolectric.buildActivity(
				SaveCleanCopyActivity.class, start).setup().get();

		Intent create = null;
		long until = System.currentTimeMillis() + 20_000L;
		while (create == null && System.currentTimeMillis() < until) {
			shadowOf(Looper.getMainLooper()).idle();
			create = shadowOf(screen).getNextStartedActivity();
			if (create == null) Thread.sleep(10L);
		}
		assertNotNull("the save screen never asked where to save", create);
		assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.getAction());
		String name = create.getStringExtra(Intent.EXTRA_TITLE);
		assertNotNull(name);
		String kind = app.getString(R.string.attachment_kind_text);
		assertTrue(name, name.matches(java.util.regex.Pattern.quote(kind)
				+ " \\d{4}-\\d{2}-\\d{2}_\\d{6}\\.txt"));
	}
}
