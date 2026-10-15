package com.professor.zerion.android.login;

import android.app.Application;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.account.WelcomeActivity;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LostPasswordEraseTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long TIMEOUT_MS = 20_000;

	private final Application app = RuntimeEnvironment.getApplication();

	private File decoyKey;
	private File hiddenKey;
	private File hiddenDb;
	private File walletFile;

	private static File leftover(File f) throws IOException {
		File dir = f.getParentFile();
		assertTrue(dir.isDirectory() || dir.mkdirs());
		Files.write(f.toPath(), "kept".getBytes(StandardCharsets.UTF_8));
		return f;
	}

	@Before
	@After
	public void clearMarkers() {
		File marker = new File(app.getFilesDir().getAbsoluteFile()
				.getParentFile(), "erase.requested");
		if (marker.exists()) assertTrue(marker.delete());
		for (String name : new String[] {"login.lockout",
				"password.check.lockout"}) {
			File lockout = new File(app.getFilesDir(), name);
			if (lockout.exists()) assertTrue(lockout.delete());
		}
	}

	private void twoProfiles() throws IOException {
		decoyKey = leftover(new File(app.getFilesDir(),
				"profiles/decoy/key/db.key"));
		hiddenKey = leftover(new File(app.getFilesDir(),
				"profiles/hidden/key/db.key"));
		hiddenDb = leftover(new File(app.getFilesDir(),
				"profiles/hidden/db/db.sqlite"));
		walletFile = leftover(new File(app.getFilesDir(),
				"profiles/hidden/wallet/xmr/wallet.keys"));
	}

	private static String state(File f) {
		return f.exists() ? "kept" : "erased";
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private AlertDialog askForgotten(ActivityController<StartupActivity> c) {
		idle();
		View forgot = c.get().findViewById(R.id.btn_forgotten);
		assertNotNull(forgot);
		forgot.performClick();
		idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		assertTrue(dialog.isShowing());
		return dialog;
	}

	private static boolean welcomeShown(StartupActivity a) {
		long end = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < end) {
			idle();
			Intent next;
			while ((next = shadowOf(a).getNextStartedActivity()) != null) {
				if (next.getComponent() != null
						&& WelcomeActivity.class.getName().equals(
								next.getComponent().getClassName())) {
					return true;
				}
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	@Test
	public void lostPasswordErasesNothingWithoutTheConfirmationWord()
			throws Exception {
		twoProfiles();
		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create()
						.start().resume();
		AlertDialog dialog = askForgotten(c);
		Button delete = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
		boolean enabledAtFirst = delete.isEnabled();
		EditText word = findEditText(dialog.getWindow().getDecorView());
		assertNotNull(word);
		word.setText("delete me");
		boolean enabledOnAnotherWord = delete.isEnabled();
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();

		assertEquals("delete off, off on another word, decoy key kept,"
						+ " hidden key kept, hidden database kept, wallet kept",
				"delete " + (enabledAtFirst ? "on" : "off")
						+ ", " + (enabledOnAnotherWord ? "on" : "off")
						+ " on another word, decoy key " + state(decoyKey)
						+ ", hidden key " + state(hiddenKey)
						+ ", hidden database " + state(hiddenDb)
						+ ", wallet " + state(walletFile));
		c.pause().stop().destroy();
	}

	@Test
	public void lostPasswordWithTheWordErasesEveryProfile() throws Exception {
		twoProfiles();
		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create()
						.start().resume();
		AlertDialog dialog = askForgotten(c);
		EditText word = findEditText(dialog.getWindow().getDecorView());
		assertNotNull(word);
		word.setText(app.getString(R.string.delete_confirm_word));
		dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		boolean welcome = welcomeShown(c.get());

		assertEquals("welcome shown, decoy key erased, hidden key erased,"
						+ " hidden database erased, wallet erased",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", decoy key " + state(decoyKey)
						+ ", hidden key " + state(hiddenKey)
						+ ", hidden database " + state(hiddenDb)
						+ ", wallet " + state(walletFile));
		c.pause().stop().destroy();
	}

	@Nullable
	private static EditText findEditText(View v) {
		if (v instanceof EditText) return (EditText) v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				EditText e = findEditText(g.getChildAt(i));
				if (e != null) return e;
			}
		}
		return null;
	}
}
