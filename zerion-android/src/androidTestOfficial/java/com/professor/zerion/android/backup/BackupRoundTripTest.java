package com.professor.zerion.android.backup;

import org.zerionproject.core.api.crypto.SecretKey;

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

		accountManager.deleteAccount();
		accountManager.createAccount(USERNAME, PASSWORD.clone());
		SecretKey key = accountManager.getDatabaseKey();
		assertTrue("no db key after createAccount", key != null);
		lifecycleManager.startServices(key);
		lifecycleManager.waitForStartup();

		byte[] backup = backupManager.exportAccount(backupPass.clone());
		assertTrue("empty backup", backup.length > 64);

		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();

		try {
			backupManager.importAccount(backup, backupPass.clone(),
					newPass.clone());
		} catch (BackupException e) {
			String why = ((org.zerionproject.core.account.AndroidAccountManager)
					accountManager).getLastProfileCreationError();
			throw new AssertionError("import failed: " + e.reason
					+ " / cause=" + why, e);
		}

		accountManager.signIn(newPass.clone());
		assertTrue("no db key after import+signIn",
				accountManager.hasDatabaseKey());

		SecretKey key2 = accountManager.getDatabaseKey();
		lifecycleManager.startServices(key2);
		lifecycleManager.waitForStartup();
	}
}
