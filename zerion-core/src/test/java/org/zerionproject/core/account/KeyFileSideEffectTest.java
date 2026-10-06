package org.zerionproject.core.account;

import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.annotation.Nullable;

import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class KeyFileSideEffectTest extends PasswordChangeFixture {

	private static final String PRIMARY = "db.key";
	private static final String BACKUP = "db.key.bak";
	private static final String STATE = "db.key.state";

	private static int next(int[] counter) {
		return ++counter[0];
	}

	private String signIn(String password) {
		AccountManagerImpl m = manager();
		try {
			m.signIn(password.toCharArray());
			SecretKey k = m.getDatabaseKey();
			return k != null && Arrays.equals(keyBytes, k.getBytes())
					? "unlocks" : "unlocks a different key";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private String allFiles() {
		return readRaw(keyFile(PRIMARY)) + "|" + readRaw(keyFile(BACKUP))
				+ "|" + readRaw(keyFile(STATE));
	}

	@Test
	public void aChangeWhoseFilesCannotBeRealignedIsNotReportedAsUnchanged() {
		int[] primary = {0};
		int[] backup = {0};
		int[] state = {0};
		AccountManagerImpl m = new AccountManagerImpl(config, crypto, null) {
			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				String n = f.getName();
				if (n.equals(PRIMARY) && next(primary) == 1) {
					throw new IOException("injected: new primary refused");
				}
				if (n.equals(BACKUP) && next(backup) == 2) {
					throw new IOException("injected: backup realign refused");
				}
				if (n.equals(STATE) && next(state) == 2) {
					throw new IOException("injected: state realign refused");
				}
				super.writeKeyFile(f, bytes);
			}
		};
		String reported = change(m);
		String after = files();
		String newFirst = signIn(NEW);
		String oldThen = signIn(OLD);
		expect("reported=KEY_REPLACEMENT_UNCERTAIN after change: primary=old"
						+ " backup=new; new tried first: unlocks; old then:"
						+ " INVALID_PASSWORD",
				"reported=" + reported + " after change: " + after
						+ "; new tried first: " + newFirst + "; old then: "
						+ oldThen);
	}

	private static final class FailingStrengthener
			implements KeyStrengthener {

		int calls = 0;
		int failFrom = Integer.MAX_VALUE;

		@Override
		public boolean isInitialised() {
			return true;
		}

		@Override
		public SecretKey strengthenKey(SecretKey k) {
			calls++;
			if (calls >= failFrom) {
				throw new IllegalStateException("injected: device locked");
			}
			byte[] b = k.getBytes().clone();
			for (int i = 0; i < b.length; i++) b[i] ^= (byte) 0x5A;
			return new SecretKey(b);
		}

		@Override
		public void discardKeyBeforeFirstAccount() {
		}
	}

	@Test
	public void aStrengthenerThatStopsAfterTheWriteLeavesTheOldPassword()
			throws Exception {
		FailingStrengthener s = new FailingStrengthener();
		File dir = new File(testDir, "strengthened");
		DatabaseConfig cfg = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return new File(dir, "db");
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return new File(dir, "key");
			}

			@Nullable
			@Override
			public KeyStrengthener getKeyStrengthener() {
				return s;
			}
		};
		File keyDir = cfg.getDatabaseKeyDirectory();
		org.junit.Assert.assertTrue(keyDir.mkdirs());
		String oldStrengthened = toHexString(crypto.encryptWithPassword(
				keyBytes.clone(), OLD.toCharArray(), s));
		writeRaw(new File(keyDir, PRIMARY), oldStrengthened);
		writeRaw(new File(keyDir, BACKUP), oldStrengthened);
		s.calls = 0;
		s.failFrom = 3;
		String reported = change(new AccountManagerImpl(cfg, crypto, null));
		s.failFrom = Integer.MAX_VALUE;
		boolean primaryIsOld = oldStrengthened.equals(
				readRaw(new File(keyDir, PRIMARY)));
		boolean backupIsOld = oldStrengthened.equals(
				readRaw(new File(keyDir, BACKUP)));
		String newAfter = attemptWith(cfg, NEW);
		String oldAfter = attemptWith(cfg, OLD);
		expect("reported=KEY_REPLACEMENT_FAILED primary is old=true backup"
						+ " is old=true; new: INVALID_PASSWORD; old: unlocks",
				"reported=" + reported + " primary is old=" + primaryIsOld
						+ " backup is old=" + backupIsOld + "; new: "
						+ newAfter + "; old: " + oldAfter);
	}

	private String attemptWith(DatabaseConfig cfg, String password) {
		AccountManagerImpl m = new AccountManagerImpl(cfg, crypto, null);
		try {
			m.signIn(password.toCharArray());
			SecretKey k = m.getDatabaseKey();
			return k != null && Arrays.equals(keyBytes, k.getBytes())
					? "unlocks" : "unlocks a different key";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private String signInWithAFailedRepair() {
		int[] primaryWrites = {0};
		AccountManagerImpl m = new AccountManagerImpl(config, crypto, null) {
			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				if (f.getName().equals(PRIMARY) && next(primaryWrites) == 1) {
					throw new IOException("injected: primary repair refused");
				}
				super.writeKeyFile(f, bytes);
			}
		};
		String first;
		try {
			m.signIn(OLD.toCharArray());
			first = "signed in";
		} catch (DecryptionException e) {
			first = e.getDecryptionResult().name();
		}
		return "first=" + first + " files: " + files() + " state is old="
				+ AccountManagerImpl.keyState(oldHex).equals(
						readRaw(keyFile(STATE)))
				+ " restart with old: " + signIn(OLD);
	}

	@Test
	public void aFailedRepairNeverOverwritesTheBackupThatOpened()
			throws Exception {
		manager().signIn(OLD.toCharArray());
		String altered = oldHex.substring(0, 80)
				+ (oldHex.charAt(80) == '0' ? '1' : '0')
				+ oldHex.substring(81);
		writeRaw(keyFile(PRIMARY), altered);
		expect("first=signed in files: primary=other backup=old state is"
				+ " old=true restart with old: unlocks",
				signInWithAFailedRepair());
	}

	@Test
	public void aFailedRepairOfAMalformedPrimaryKeepsTheBackupThatOpened()
			throws Exception {
		manager().signIn(OLD.toCharArray());
		writeRaw(keyFile(PRIMARY), "ff" + oldHex.substring(2));
		expect("first=signed in files: primary=other backup=old state is"
				+ " old=true restart with old: unlocks",
				signInWithAFailedRepair());
	}

	@Test
	public void checkingAPasswordNeverWritesAKeyFile() throws Exception {
		AccountManagerImpl m = manager();
		m.signIn(OLD.toCharArray());
		byte[] otherKey = getRandomBytes(SecretKey.LENGTH);
		String planted = toHexString(crypto.encryptWithPassword(otherKey,
				"intruder".toCharArray(), null));
		writeRaw(keyFile(BACKUP), planted);
		writeRaw(keyFile(STATE), AccountManagerImpl.keyState(planted));
		String before = allFiles();
		String gate;
		try {
			m.verifyPassword("intruder".toCharArray());
			gate = "GRANTED";
		} catch (DecryptionException e) {
			gate = e.getDecryptionResult().name();
		}
		boolean unchanged = before.equals(allFiles());
		expect("gate=INVALID_CIPHERTEXT files unchanged=true restart with"
						+ " old: unlocks",
				"gate=" + gate + " files unchanged=" + unchanged
						+ " restart with old: " + signIn(OLD));
	}

	@Test
	public void checkingTheRightPasswordRepairsNothingEither()
			throws Exception {
		AccountManagerImpl m = manager();
		m.signIn(OLD.toCharArray());
		writeRaw(keyFile(PRIMARY), "not a key");
		String before = allFiles();
		String gate;
		try {
			m.verifyPassword(OLD.toCharArray());
			gate = "GRANTED";
		} catch (DecryptionException e) {
			gate = e.getDecryptionResult().name();
		}
		expect("gate=GRANTED files unchanged=true", "gate=" + gate
				+ " files unchanged=" + before.equals(allFiles()));
	}
}
