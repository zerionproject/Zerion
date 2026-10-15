package com.professor.zerion.android.login;

import android.app.Application;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LostPasswordWarningTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long TIMEOUT_MS = 20_000;

	private final Application app = RuntimeEnvironment.getApplication();

	private File keyFile() {
		return new File(app.getFilesDir(), "profiles/default/key/db.key");
	}

	@After
	public void removeAccount() {
		File key = keyFile();
		if (key.exists()) assertTrue(key.delete());
	}

	@Test
	public void forgettingThePasswordSaysThatVaultAndWalletsGoToo()
			throws Exception {
		File key = keyFile();
		assertTrue(key.getParentFile().isDirectory()
				|| key.getParentFile().mkdirs());
		Files.write(key.toPath(), "00".getBytes(StandardCharsets.UTF_8));

		ActivityController<StartupActivity> c = Robolectric.buildActivity(
				StartupActivity.class).create().start().resume();
		View forgotten = null;
		long end = System.currentTimeMillis() + TIMEOUT_MS;
		while (forgotten == null && System.currentTimeMillis() < end) {
			shadowOf(Looper.getMainLooper()).idle();
			forgotten = c.get().findViewById(R.id.btn_forgotten);
			if (forgotten == null) Thread.sleep(20);
		}
		assertNotNull("the sign-in screen is shown", forgotten);

		forgotten.performClick();
		shadowOf(Looper.getMainLooper()).idle();

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		TextView message = dialog.findViewById(android.R.id.message);
		assertNotNull(message);
		assertTrue(message.getText().toString().contains(
				app.getString(R.string.startup_erase_everything_confirm)));
		c.destroy();
	}
}
