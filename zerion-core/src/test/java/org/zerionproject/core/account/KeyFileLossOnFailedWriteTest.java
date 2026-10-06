package org.zerionproject.core.account;

import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;

import java.io.File;
import java.io.IOException;

import javax.annotation.Nullable;

import static org.junit.Assert.assertTrue;

public class KeyFileLossOnFailedWriteTest extends PasswordChangeFixture {

	private void primaryMissingBackupVouched() throws IOException {
		assertTrue(keyFile("db.key").delete());
		writeRaw(keyFile("db.key.state"), AccountManagerImpl.keyState(oldHex));
	}

	private AccountManagerImpl primaryRefused(DatabaseConfig cfg) {
		return new AccountManagerImpl(cfg, crypto, null) {
			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				if (f.getName().equals("db.key")) {
					throw new IOException("primary refused");
				}
				super.writeKeyFile(f, bytes);
			}
		};
	}

	private String keyFiles() {
		return "primary=" + classify("db.key") + " backup="
				+ classify("db.key.bak") + " state="
				+ (keyFile("db.key.state").exists() ? "present" : "missing")
				+ " account=" + manager().accountExists();
	}

	@Test
	public void aChangeWhosePrimaryFailsKeepsTheVouchedBackup()
			throws Exception {
		primaryMissingBackupVouched();
		change(primaryRefused(config));
		expect("primary=missing backup=old state=present account=true",
				keyFiles());
		expect("old unlocks, new rejected; primary=old backup=old",
				restart());
	}

	@Test
	public void aKeyUpgradeWhosePrimaryFailsKeepsTheVouchedBackup()
			throws Exception {
		primaryMissingBackupVouched();
		KeyStrengthener strengthener = new KeyStrengthener() {
			@Override
			public boolean isInitialised() {
				return true;
			}

			@Override
			public SecretKey strengthenKey(SecretKey k) {
				byte[] b = k.getBytes().clone();
				for (int i = 0; i < b.length; i++) b[i] ^= (byte) 0x5A;
				return new SecretKey(b);
			}

			@Override
			public void discardKeyBeforeFirstAccount() {
			}
		};
		DatabaseConfig strengthened = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return config.getDatabaseDirectory();
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return config.getDatabaseKeyDirectory();
			}

			@Nullable
			@Override
			public KeyStrengthener getKeyStrengthener() {
				return strengthener;
			}
		};
		try {
			primaryRefused(strengthened).signIn(OLD.toCharArray());
		} catch (DecryptionException e) {
			throw new AssertionError(e.getDecryptionResult().name());
		}
		expect("primary=missing backup=old state=present account=true",
				keyFiles());
	}
}
