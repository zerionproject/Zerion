package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.util.IoUtils;

import java.io.File;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackupFragmentGateTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	@Test
	public void inASessionEveryBackupCardAsksForTheAccountPasswordFirst() {
		Context app = RuntimeEnvironment.getApplication();
		AccountManager accountManager = com.professor.zerion.android.AppModule
				.getAndroidComponent(app).accountManager();
		assertTrue(accountManager.createAccount("Dana",
				"decoy password".toCharArray()));
		assertTrue(accountManager.hasDatabaseKey());

		FragmentActivity host = Robolectric.buildActivity(
				FragmentActivity.class).setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, new BackupFragment())
				.commitNow();
		shadowOf(Looper.getMainLooper()).idle();

		int[] cards = {R.id.import_card, R.id.export_card,
				R.id.transfer_receive_card, R.id.transfer_send_card};
		for (int card : cards) {
			ShadowDialog.reset();
			host.findViewById(card).performClick();
			shadowOf(Looper.getMainLooper()).idle();
			Dialog dialog = ShadowDialog.getLatestDialog();
			assertNotNull("card " + card + " asks for the password first",
					dialog);
			assertNull("card " + card + " opened nothing yet",
					shadowOf(host).getNextStartedActivity());
			assertNull("card " + card + " navigated nowhere yet",
					host.getSupportFragmentManager()
							.findFragmentById(R.id.fragmentContainer));
			dialog.dismiss();
		}
	}
}
