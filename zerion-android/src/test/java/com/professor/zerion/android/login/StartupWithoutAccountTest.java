package com.professor.zerion.android.login;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
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
import org.zerionproject.core.account.ProfileManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

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
		assertTrue(dir.isDirectory() || dir.mkdirs());
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

	private boolean sawWelcome;

	private boolean welcomeStarted(StartupActivity a) {
		Intent next;
		while ((next = shadowOf(a).getNextStartedActivity()) != null) {
			if (next.getComponent() != null
					&& WelcomeActivity.class.getName().equals(
							next.getComponent().getClassName())) {
				sawWelcome = true;
			}
		}
		return sawWelcome;
	}

	private static boolean asked() {
		Dialog d = ShadowDialog.getLatestDialog();
		return d != null && d.isShowing();
	}

	private boolean start(ActivityController<StartupActivity> c)
			throws Exception {
		StartupActivity a = c.get();
		long end = System.currentTimeMillis() + TIMEOUT_MS;
		shadowOf(Looper.getMainLooper()).idle();
		while (!welcomeStarted(a) && !asked()
				&& System.currentTimeMillis() < end) {
			Thread.sleep(20);
			shadowOf(Looper.getMainLooper()).idle();
		}
		shadowOf(Looper.getMainLooper()).idle();
		return welcomeStarted(a);
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
		boolean welcome = start(c);
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
		boolean welcome = start(c);
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
		start(c);
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
		boolean welcome = start(c);
		assertEquals("welcome shown, database erased, vault erased,"
						+ " wallet files erased",
				(welcome ? "welcome shown" : "welcome not shown")
						+ ", database " + state(db)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile));
		c.pause().stop().destroy();
	}

	@Test
	public void keepingWhatNoAccountKeyOpensDeletesNothing() throws Exception {
		leaveData();

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create()
						.start().resume();
		start(c);
		AlertDialog asked = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(asked);
		asked.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		boolean closed = c.get().isFinishing();
		new ProfileManager(app);
		assertEquals("closed, database kept, key state kept, vault kept,"
						+ " wallet files kept, erase request absent",
				(closed ? "closed" : "open")
						+ ", database " + state(db)
						+ ", key state " + state(keyState)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile)
						+ ", erase request "
						+ (eraseRequest().exists() ? "present" : "absent"));
		c.pause().stop().destroy();
	}

	@Test
	public void eraseWaitsForTheConfirmationWordAndCancelAsksAgain()
			throws Exception {
		leaveData();

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create()
						.start().resume();
		start(c);
		AlertDialog asked = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(asked);
		asked.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
		assertTrue(confirm != asked);
		Button delete = confirm.getButton(DialogInterface.BUTTON_NEGATIVE);
		boolean enabledAtFirst = delete.isEnabled();
		EditText word = findEditText(confirm.getWindow().getDecorView());
		assertNotNull(word);
		word.setText("erase");
		boolean enabledOnAnotherWord = delete.isEnabled();
		confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog again = (AlertDialog) ShadowDialog.getLatestDialog();
		boolean askedAgain = again != confirm && again.isShowing();
		assertEquals("delete off, off on another word, asked again,"
						+ " database kept, vault kept, wallet files kept",
				"delete " + (enabledAtFirst ? "on" : "off")
						+ ", " + (enabledOnAnotherWord ? "on" : "off")
						+ " on another word, "
						+ (askedAgain ? "asked again" : "not asked again")
						+ ", database " + state(db)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile));
		c.pause().stop().destroy();
	}

	@Test
	public void dataBesideAnAccountGoesToSignInAndKeepsTheAccount()
			throws Exception {
		leaveData();
		File accountKey = leftover(new File(app.getFilesDir(),
				"profiles/u1/key/db.key"));
		File accountDb = leftover(new File(app.getFilesDir(),
				"profiles/u1/db/db.sqlite"));

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create();
		StartupViewModel.StartupDecision decision = decide(c.get());
		boolean welcome = welcomeStarted(c.get());
		new ProfileManager(app);
		assertEquals("SIGN_IN, not asked, welcome not shown, account key"
						+ " kept, account database kept, vault kept, wallet"
						+ " files kept, keyless profile removed",
				decision + ", " + (asked() ? "asked" : "not asked")
						+ ", " + (welcome ? "welcome shown"
						: "welcome not shown")
						+ ", account key " + state(accountKey)
						+ ", account database " + state(accountDb)
						+ ", vault " + state(vaultHeader)
						+ ", wallet files " + state(walletFile)
						+ ", keyless profile " + (new File(app.getFilesDir(),
						"profiles/default").exists() ? "kept" : "removed"));
		c.destroy();
	}

	private static StartupViewModel.StartupDecision decide(StartupActivity a)
			throws Exception {
		Field f = StartupActivity.class.getDeclaredField("viewModel");
		f.setAccessible(true);
		StartupViewModel viewModel = (StartupViewModel) f.get(a);
		AtomicReference<StartupViewModel.StartupDecision> decision =
				new AtomicReference<>();
		viewModel.decideStartAsync(decision::set);
		long end = System.currentTimeMillis() + TIMEOUT_MS;
		while (decision.get() == null && System.currentTimeMillis() < end) {
			Thread.sleep(20);
			shadowOf(Looper.getMainLooper()).idle();
		}
		shadowOf(Looper.getMainLooper()).idle();
		return decision.get();
	}

	@Test
	public void withNothingOfAUsersLeftTheSettingsAreClearedForAFreshStart()
			throws Exception {
		setEraseSetting();

		ActivityController<StartupActivity> c =
				Robolectric.buildActivity(StartupActivity.class).create();
		boolean welcome = start(c);
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
