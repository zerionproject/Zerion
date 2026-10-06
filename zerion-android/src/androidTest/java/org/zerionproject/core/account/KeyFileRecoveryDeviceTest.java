package org.zerionproject.core.account;

import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class KeyFileRecoveryDeviceTest extends PasswordChangeDeviceFixture {

	private static final String PRIMARY = "db.key";
	private static final String BACKUP = "db.key.bak";
	private static final String STATE = "db.key.state";

	private void signInOnce() throws Exception {
		manager().signIn(OLD.toCharArray());
	}

	private String newHex() {
		return toHexString(crypto.encryptWithPassword(keyBytes.clone(),
				NEW.toCharArray(), null));
	}

	private String state(String hex) {
		return AccountManagerImpl.keyState(hex);
	}

	private void put(String name, String content) throws IOException {
		writeRaw(keyFile(name), content);
	}

	private static String malformed(String hex) {
		return "ff" + hex.substring(2);
	}

	private String attempt(String password) {
		AccountManagerImpl m = manager();
		String outcome;
		try {
			m.signIn(password.toCharArray());
			SecretKey key = m.getDatabaseKey();
			outcome = key != null && Arrays.equals(keyBytes, key.getBytes())
					? "unlocks" : "unlocks a different key";
		} catch (DecryptionException e) {
			outcome = e.getDecryptionResult().name();
		}
		return outcome + " (failed attempts " + m.failedSignInAttempts()
				+ "); " + allFiles();
	}

	private String allFiles() {
		return files() + " state=" + classifyState();
	}

	private String classifyState() {
		String s = readRaw(keyFile(STATE));
		if (s == null) return "missing";
		if (s.equals(state(oldHex))) return "old";
		String primary = readRaw(keyFile(PRIMARY));
		String backup = readRaw(keyFile(BACKUP));
		if (primary != null && s.equals(state(primary))) return "primary";
		if (backup != null && s.equals(state(backup))) return "backup";
		return "other";
	}

	@Test
	public void aValidPrimaryAndBackupSignInAndStayAligned() throws Exception {
		signInOnce();
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
		expect("INVALID_PASSWORD (failed attempts 1); primary=old backup=old"
				+ " state=old", attempt(NEW));
	}

	@Test
	public void aMalformedPrimaryIsRepairedFromTheProvenBackup()
			throws Exception {
		signInOnce();
		put(PRIMARY, malformed(oldHex));
		expect("INVALID_PASSWORD (failed attempts 1); primary=other"
				+ " backup=old state=old", attempt(NEW));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void aPrimaryThatIsNotHexOrTruncatedIsRepairedToo()
			throws Exception {
		signInOnce();
		put(PRIMARY, "not a key");
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
		put(PRIMARY, oldHex.substring(0, 20));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void aMissingPrimaryIsRestoredFromTheProvenBackup()
			throws Exception {
		signInOnce();
		assertTrue(keyFile(PRIMARY).delete());
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void aBackupWithoutAKeyStateIsNotUsed() throws Exception {
		assertTrue(keyFile(PRIMARY).delete());
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=missing"
				+ " backup=old state=missing", attempt(OLD));
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=missing"
				+ " backup=old state=missing", attempt(NEW));
	}

	@Test
	public void aDamagedBackupBehindAValidPrimaryIsRepaired()
			throws Exception {
		signInOnce();
		put(BACKUP, malformed(oldHex));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void damageToBothFilesFailsClosed() throws Exception {
		signInOnce();
		put(PRIMARY, malformed(oldHex));
		put(BACKUP, "not a key");
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=other"
				+ " backup=other state=old", attempt(OLD));
	}

	@Test
	public void aPrimaryTheKeyStateDoesNotVouchForGivesWayToTheVouchedBackup()
			throws Exception {
		String newHex = newHex();
		put(PRIMARY, newHex);
		put(BACKUP, oldHex);
		put(STATE, state(oldHex));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
		expect("INVALID_PASSWORD (failed attempts 1); primary=old backup=old"
				+ " state=old", attempt(NEW));
	}

	@Test
	public void aVouchedPrimaryNeverGivesWayToTheBackup() throws Exception {
		String newHex = newHex();
		put(PRIMARY, newHex);
		put(BACKUP, oldHex);
		put(STATE, state(newHex));
		expect("INVALID_PASSWORD (failed attempts 1); primary=new backup=old"
				+ " state=primary", attempt(OLD));
		expect("unlocks (failed attempts 0); primary=new backup=new"
				+ " state=primary", attempt(NEW));
	}

	@Test
	public void aWellFormedButAlteredPrimaryDoesNotCountTheRightPassword()
			throws Exception {
		signInOnce();
		String altered = oldHex.substring(0, 80)
				+ (oldHex.charAt(80) == '0' ? '1' : '0')
				+ oldHex.substring(81);
		put(PRIMARY, altered);
		expect("INVALID_PASSWORD (failed attempts 1); primary=other"
				+ " backup=old state=old", attempt(NEW));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void aStaleBackupBehindADamagedPrimaryFailsClosed()
			throws Exception {
		String newHex = newHex();
		put(PRIMARY, malformed(newHex));
		put(BACKUP, oldHex);
		put(STATE, state(newHex));
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=other"
				+ " backup=old state=other", attempt(OLD));
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=other"
				+ " backup=old state=other", attempt(NEW));
		assertTrue(keyFile(STATE).delete());
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=other"
				+ " backup=old state=missing", attempt(OLD));
	}

	@Test
	public void thePasswordOfTheVouchedBackupOpensAnUncommittedPrimary()
			throws Exception {
		String newHex = newHex();
		put(PRIMARY, oldHex);
		put(BACKUP, newHex);
		put(STATE, state(newHex));
		expect("unlocks (failed attempts 0); primary=new backup=new"
				+ " state=primary", attempt(NEW));
		expect("INVALID_PASSWORD (failed attempts 1); primary=new backup=new"
				+ " state=primary", attempt(OLD));
	}

	@Test
	public void aChangeInterruptedAfterTheBackupKeepsTheOldPassword()
			throws Exception {
		signInOnce();
		interruptAfter(BACKUP);
		put(PRIMARY, malformed(oldHex));
		expect("KEY_FILES_DAMAGED (failed attempts 0); primary=other"
				+ " backup=new state=old", attempt(OLD));
	}

	@Test
	public void aChangeInterruptedAfterTheBackupWithAnIntactPrimary()
			throws Exception {
		signInOnce();
		interruptAfter(BACKUP);
		expect("INVALID_PASSWORD (failed attempts 1); primary=old backup=new"
				+ " state=old", attempt(NEW));
		expect("unlocks (failed attempts 0); primary=old backup=old state=old",
				attempt(OLD));
	}

	@Test
	public void aChangeInterruptedAfterTheKeyStateOpensWithTheNewPassword()
			throws Exception {
		signInOnce();
		interruptAfter(STATE);
		put(PRIMARY, malformed(oldHex));
		expect("INVALID_PASSWORD (failed attempts 1); primary=other"
				+ " backup=new state=backup", attempt(OLD));
		expect("unlocks (failed attempts 0); primary=new backup=new"
				+ " state=primary", attempt(NEW));
	}

	@Test
	public void aChangeInterruptedAfterThePrimaryIsComplete()
			throws Exception {
		signInOnce();
		interruptAfter(PRIMARY);
		expect("INVALID_PASSWORD (failed attempts 1); primary=new backup=new"
				+ " state=primary", attempt(OLD));
		expect("unlocks (failed attempts 0); primary=new backup=new"
				+ " state=primary", attempt(NEW));
	}

	@Test
	public void aRecoveredAccountChangesItsPasswordNormally()
			throws Exception {
		signInOnce();
		put(PRIMARY, "not a key");
		AccountManagerImpl m = manager();
		m.changePassword(OLD.toCharArray(), NEW.toCharArray());
		expect("unlocks (failed attempts 0); primary=new backup=new"
				+ " state=primary", attempt(NEW));
		expect("INVALID_PASSWORD (failed attempts 1); primary=new backup=new"
				+ " state=primary", attempt(OLD));
	}

	private void interruptAfter(String name) {
		AccountManagerImpl m = new AccountManagerImpl(config, crypto, null) {
			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				super.writeKeyFile(f, bytes);
				if (f.getName().equals(name)) {
					throw new SimulatedProcessDeath();
				}
			}
		};
		try {
			m.changePassword(OLD.toCharArray(), NEW.toCharArray());
			throw new AssertionError("the change was not interrupted");
		} catch (SimulatedProcessDeath expected) {
		} catch (DecryptionException e) {
			throw new AssertionError(e);
		}
	}
}
