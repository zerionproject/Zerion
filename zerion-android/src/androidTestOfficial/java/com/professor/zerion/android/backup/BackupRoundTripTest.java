package com.professor.zerion.android.backup;

import org.briarproject.bramble.api.crypto.SecretKey;

import com.professor.zerion.android.BriarUiTestComponent;
import com.professor.zerion.android.UiTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class BackupRoundTripTest extends UiTest {

	@Inject
	AccountBackupManager backupManager;

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void exportThenImportThenSignIn() throws Exception {
		char[] backupPass = "backup-pass-123".toCharArray();
		char[] newPass = "new-device-pass-456".toCharArray();

		// 1. OLD phone: fresh signed-in account with an open DB.
		accountManager.deleteAccount();
		accountManager.createAccount(USERNAME, PASSWORD.clone());
		SecretKey key = accountManager.getDatabaseKey();
		assertTrue("no db key after createAccount", key != null);
		lifecycleManager.startServices(key);
		lifecycleManager.waitForStartup();

		// 2. Export a backup (the step the user did successfully).
		byte[] backup = backupManager.exportAccount(backupPass.clone());
		assertTrue("empty backup", backup.length > 64);

		// 3. Shut the old account down cleanly. Keep the keystore strengthener
		//    key valid (a real importing device has its own working keystore),
		//    so this isolates the import + sign-in logic from keystore recovery.
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();

		// 4. Import under a NEW device password (the failing user step).
		try {
			backupManager.importAccount(backup, backupPass.clone(),
					newPass.clone());
		} catch (BackupException e) {
			String why = ((org.briarproject.bramble.account.AndroidAccountManager)
					accountManager).getLastProfileCreationError();
			throw new AssertionError("import failed: " + e.reason
					+ " / cause=" + why, e);
		}

		// 5. Sign in with the new password. This is where the device showed
		//    "Cannot Check Password".
		accountManager.signIn(newPass.clone());
		assertTrue("no db key after import+signIn",
				accountManager.hasDatabaseKey());

		// 6. Bring the imported account fully up to confirm the DB opens with
		//    the imported key.
		SecretKey key2 = accountManager.getDatabaseKey();
		lifecycleManager.startServices(key2);
		lifecycleManager.waitForStartup();
	}
}
