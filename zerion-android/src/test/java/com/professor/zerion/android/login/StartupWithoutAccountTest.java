package com.professor.zerion.android.login;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
import org.zerionproject.core.account.ProfileManager;

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
public class StartupWithoutAccountTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long TIMEOUT_MS = 20_000;

	private final Application app = RuntimeEnvironment.getApplication();

	private static File leftover(File f) throws IOException {
		File dir = f.getParentFile();
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		Files.write(f.toPath(), "left over".getBytes(StandardCharsets.UTF_8));
		return f;
	}

	private File db;
	private File keyState;
	private File vaultHeader;
	private File walletFile;

	private void leaveData() throws IOException {
		db = leftover(new File(app.getFilesDir(),
				"profiles/default/db/db.sqlite"));
		keyState = leftover(new File(app.getFilesDir(),
				"profiles/default/key/db.key.state"));
		vaultHeader = leftover(new File(app.getNoBackupFilesDir(),
				"vault/vault.header"));
		walletFile = leftover(new File(app.getNoBackupFilesDir(),
				"xmr/wallet.keys"));
	}

	private void setEraseSetting() {
		assertTrue(app.getSharedPreferences("secure_prefs_v2",
				Context.MODE_PRIVATE).edit().putBoolean("bf_wipe", true)
				.commit());
	}

	private String eraseSetting() {
		return app.getSharedPreferences("secure_prefs_v2",
				Context.MODE_PRIVATE).getBoolean("bf_wipe", false)
				? "kept" : "erased";
	}

	private File eraseRequest() {
		return new File(app.getFilesDir().getAbsoluteFile().getParentFile(),
				"erase.requested");
	}

	private static String state(File f) {
		return f.exists() ? "kept" : "erased";
	}

	private boolean start(ActivityController<StartupActivity> c,
			long settleMs) throws Exception {
		StartupActivity a = c.get();
		boolean welcome = false;
		long end = System.currentTimeMillis() + settleMs;
		while (System.currentTimeMillis() < end && !welcome) {
			shadowOf(Looper.getMainLooper()).idle();
			Intent next = shadowOf(a).getNextStartedActivity();
			if (next != null && next.getComponent() != null
					&& WelcomeActivity.class.getName().equals(
							next.getComponent().getClassName())) {
				welcome = true;
			}
			Thread.sleep(20);
		}
		return welcome;
	}

	@Before
	@After
	public void clearInheritedMarkers() {
		File marker = eraseRequest();
		if (marker.exists()) assertTrue(marker.delete());
		File setupMarker = new File(app.getFilesDir(),
				"profiles/default/db/db.setup-incomplete");
		if (setupMarker.exists()) assertTrue(setupMarker.delete());
		for (String name : new String[] {"login.lockout",
				"password.check.lockout"}) {
			File lockout = new File(app.getFilesDir(), name);
			if (lockout.exists()) assertTrue(lockout.delete());
		}
	}

	@Test
	public void aRequestedEraseIsCompletedAtTheNextStart() throws Exception {
		leaveData();
		setEraseSetting();
		Files.write(eraseRequest().toPath(), new byte[] {'1'});

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create();
		boolean welcome = start(c, TIMEOUT_MS);
		assertEquals("welcome shown, database erased, vault erased,"
						+ " wallet files erased, erase setting erased,"
						+ " erase request erased",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", database " + state(db)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile)
						+ ", erase setting " + eraseSetting()
						+ ", erase request " + state(eraseRequest()));
		c.destroy();
	}

	@Test
	public void dataThatNoAccountKeyOpensIsKeptAndTheUserIsAsked()
			throws Exception {
		leaveData();
		setEraseSetting();
		new ProfileManager(app);

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create();
		boolean welcome = start(c, 3_000);
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		String shown = dialog != null && dialog.isShowing()
				? "asked" : "not asked";
		assertEquals("welcome not shown, asked, database kept, key state"
						+ " kept, vault kept, wallet files kept, erase setting"
						+ " kept",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", " + shown
						+ ", database " + state(db)
						+ ", key state " + state(keyState)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile)
						+ ", erase setting " + eraseSetting());
		c.destroy();
	}

	@Test
	public void theUserCanEraseWhatNoAccountKeyOpens() throws Exception {
		leaveData();

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create()
						.start().resume();
		start(c, 3_000);
		AlertDialog asked = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(asked);
		asked.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
		assertTrue(confirm != asked);
		EditText word = findEditText(confirm.getWindow().getDecorView());
		assertNotNull(word);
		word.setText(app.getString(R.string.delete_confirm_word));
		confirm.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		boolean welcome = start(c, TIMEOUT_MS);
		assertEquals("welcome shown, database erased, vault erased,"
						+ " wallet files erased",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", database " + state(db)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile));
		c.pause().stop().destroy();
	}

	@Test
	public void withNothingOfAUsersLeftTheSettingsAreClearedForAFreshStart()
			throws Exception {
		setEraseSetting();

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create();
		boolean welcome = start(c, TIMEOUT_MS);
		assertEquals("welcome shown, erase setting erased",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", erase setting " + eraseSetting());
		c.destroy();
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
