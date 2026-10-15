package com.professor.zerion.android.settings;

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
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.util.Locale;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupFileNameTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	private static FragmentActivity host() {
		FragmentActivity host = Robolectric.buildActivity(
				FragmentActivity.class).setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, new BackupFragment())
				.commitNow();
		shadowOf(Looper.getMainLooper()).idle();
		return host;
	}

	@Test
	public void theSuggestedBackupNameDoesNotNameTheApp() {
		FragmentActivity host = host();
		host.findViewById(R.id.export_card).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Intent create = shadowOf(host).getNextStartedActivity();
		assertNotNull(create);
		assertEquals(Intent.ACTION_CREATE_DOCUMENT, create.getAction());
		String name = create.getStringExtra(Intent.EXTRA_TITLE);
		assertEquals(BackupFragment.EXPORT_FILE_NAME, name);
		String lower = name.toLowerCase(Locale.ROOT);
		assertFalse(lower.contains("zerion"));
		assertFalse(lower.endsWith(".zbk"));
	}

	@Test
	public void importingDoesNotDependOnTheNameOrExtension() {
		FragmentActivity host = host();
		host.findViewById(R.id.import_card).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		Intent open = shadowOf(host).getNextStartedActivity();
		assertNotNull(open);
		assertEquals(Intent.ACTION_OPEN_DOCUMENT, open.getAction());
		assertEquals("*/*", open.getType());
		assertArrayEquals(new String[] {"*/*"},
				open.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
	}
}
