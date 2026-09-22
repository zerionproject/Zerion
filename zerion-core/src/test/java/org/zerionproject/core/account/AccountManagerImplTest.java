package org.zerionproject.core.account;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.annotation.Nullable;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class AccountManagerImplTest extends BrambleMockTestCase {

	private final DatabaseConfig databaseConfig =
			context.mock(DatabaseConfig.class);
	private final KeyStrengthener keyStrengthener =
			context.mock(KeyStrengthener.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);

	private final SecretKey key = getSecretKey();
	private final byte[] encryptedKey = getRandomBytes(123);
	private final String encryptedKeyHex = toHexString(encryptedKey);
	private final byte[] newEncryptedKey = getRandomBytes(123);
	private final String newEncryptedKeyHex = toHexString(newEncryptedKey);
	private final Identity identity = getIdentity();
	private final LocalAuthor localAuthor = identity.getLocalAuthor();
	private final String authorName = localAuthor.getName();
	private final char[] password = getRandomString(10).toCharArray();
	private final char[] newPassword = getRandomString(10).toCharArray();
	private final File testDir = getTestDirectory();
	private final File dbDir = new File(testDir, "db");
	private final File keyDir = new File(testDir, "key");
	private final File keyFile = new File(keyDir, "db.key");
	private final File keyBackupFile = new File(keyDir, "db.key.bak");

	private AccountManagerImpl accountManager;

	@Before
	public void setUp() {
		context.checking(new Expectations() {{
			allowing(databaseConfig).getDatabaseDirectory();
			will(returnValue(dbDir));
			allowing(databaseConfig).getDatabaseKeyDirectory();
			will(returnValue(keyDir));
			allowing(databaseConfig).getKeyStrengthener();
			will(returnValue(keyStrengthener));
		}});

		accountManager =
				new AccountManagerImpl(databaseConfig, crypto, identityManager);

		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testSignInThrowsExceptionIfDbKeyCannotBeLoaded() {
		try {
			accountManager.signIn(password);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_CIPHERTEXT, expected.getDecryptionResult());
		}
		assertFalse(accountManager.hasDatabaseKey());

		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testSignInThrowsExceptionIfPasswordIsWrong() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(throwException(new DecryptionException(INVALID_PASSWORD)));
		}});

		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));

		try {
			accountManager.signIn(password);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_PASSWORD, expected.getDecryptionResult());
		}
		assertFalse(accountManager.hasDatabaseKey());

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testSignInReturnsTrueIfPasswordIsRight() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(true));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
		}});

		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));

		accountManager.signIn(password);
		assertTrue(accountManager.hasDatabaseKey());
		SecretKey decrypted = accountManager.getDatabaseKey();
		assertNotNull(decrypted);
		assertArrayEquals(key.getBytes(), decrypted.getBytes());

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testSignInReEncryptsKey() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).encryptWithPassword(key.getBytes(), password,
					keyStrengthener);
			will(returnValue(newEncryptedKey));
		}});

		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));

		accountManager.signIn(password);
		assertTrue(accountManager.hasDatabaseKey());
		SecretKey decrypted = accountManager.getDatabaseKey();
		assertNotNull(decrypted);
		assertArrayEquals(key.getBytes(), decrypted.getBytes());

		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testDbKeyIsLoadedFromPrimaryFile() throws Exception {
		storeDatabaseKey(keyFile, encryptedKeyHex);

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertFalse(keyBackupFile.exists());

		assertEquals(encryptedKeyHex,
				accountManager.loadEncryptedDatabaseKey());

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testDbKeyIsLoadedFromBackupFile() throws Exception {
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		assertFalse(keyFile.exists());
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));

		assertEquals(encryptedKeyHex,
				accountManager.loadEncryptedDatabaseKey());

		assertFalse(keyFile.exists());
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testDbKeyIsNullIfNotFound() {
		assertNull(accountManager.loadEncryptedDatabaseKey());

		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testStoringDbKeyOverwritesPrimary() throws Exception {
		storeDatabaseKey(keyFile, encryptedKeyHex);

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertFalse(keyBackupFile.exists());

		assertTrue(accountManager.storeEncryptedDatabaseKey(
				newEncryptedKeyHex));

		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testStoringDbKeyOverwritesBackup() throws Exception {
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		assertFalse(keyFile.exists());
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));

		assertTrue(accountManager.storeEncryptedDatabaseKey(
				newEncryptedKeyHex));

		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testAccountExistsReturnsFalseIfDbKeyCannotBeLoaded() {
		assertFalse(accountManager.accountExists());

		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testCreateAccountStoresDbKey() throws Exception {
		context.checking(new Expectations() {{
			oneOf(identityManager).createIdentity(authorName);
			will(returnValue(identity));
			oneOf(identityManager).registerIdentity(identity);
			oneOf(crypto).generateSecretKey();
			will(returnValue(key));
			oneOf(crypto).encryptWithPassword(key.getBytes(), password,
					keyStrengthener);
			will(returnValue(encryptedKey));
		}});

		assertFalse(accountManager.hasDatabaseKey());

		assertTrue(accountManager.createAccount(authorName, password));

		assertTrue(accountManager.hasDatabaseKey());
		SecretKey dbKey = accountManager.getDatabaseKey();
		assertNotNull(dbKey);
		assertArrayEquals(key.getBytes(), dbKey.getBytes());

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testCreateAccountRejectsEmptyPassword() {
		assertFalse(accountManager.createAccount(authorName, new char[0]));
		assertFalse(accountManager.hasDatabaseKey());
		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testCreateAccountRejectsWhitespaceOnlyPassword() {
		assertFalse(accountManager.createAccount(authorName,
				"    ".toCharArray()));
		assertFalse(accountManager.hasDatabaseKey());
		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testCreateAccountRejectsNullPassword() {
		assertFalse(accountManager.createAccount(authorName, null));
		assertFalse(accountManager.hasDatabaseKey());
		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testChangePasswordRejectsEmptyNewPassword() throws Exception {
		accountManager.changePassword(password, new char[0]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testChangePasswordRejectsWhitespaceNewPassword()
			throws Exception {
		accountManager.changePassword(password, "  ".toCharArray());
	}

	@Test
	public void testChangePasswordThrowsExceptionIfDbKeyCannotBeLoaded() {
		try {
			accountManager.changePassword(password, newPassword);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_CIPHERTEXT, expected.getDecryptionResult());
		}

		assertFalse(keyFile.exists());
		assertFalse(keyBackupFile.exists());
	}

	@Test
	public void testChangePasswordThrowsExceptionIfPasswordIsWrong()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(throwException(new DecryptionException(INVALID_PASSWORD)));
		}});

		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		try {
			accountManager.changePassword(password, newPassword);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_PASSWORD, expected.getDecryptionResult());
		}

		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	@Test
	public void testChangePasswordReturnsTrueIfPasswordIsRight()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(true));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).encryptWithPassword(key.getBytes(), newPassword,
					keyStrengthener);
			will(returnValue(newEncryptedKey));
		}});

		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);

		accountManager.changePassword(password, newPassword);

		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	/** AND-06: the sign-in throttle is one persisted, monotonic counter. */
	@Test
	public void lockoutIsEnforcedBeforeTheKeyIsTouchedAndSurvivesRestart()
			throws Exception {
		java.util.concurrent.atomic.AtomicLong mono =
				new java.util.concurrent.atomic.AtomicLong(1_000);
		AccountManagerImpl m = throttled(mono);
		context.checking(new Expectations() {{
			exactly(3).of(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(throwException(new DecryptionException(INVALID_PASSWORD)));
		}});
		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);
		for (int i = 0; i < 3; i++) {
			try {
				m.signIn(password);
				fail();
			} catch (DecryptionException expected) {
			}
		}
		assertEquals(3, m.failedSignInAttempts());
		assertTrue(m.signInLockoutRemainingMs() > 0);
		try {
			m.signIn(password);
			fail("the fourth attempt must be refused without a decryption");
		} catch (DecryptionException expected) {
			assertEquals(org.zerionproject.core.api.crypto.DecryptionResult
					.INVALID_CIPHERTEXT, expected.getDecryptionResult());
		}
		AccountManagerImpl restarted = throttled(mono);
		assertTrue("the lockout is read back from the key directory",
				restarted.signInLockoutRemainingMs() > 0);
		mono.addAndGet(restarted.signInLockoutRemainingMs() + 1);
		assertEquals(0, restarted.signInLockoutRemainingMs());
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(true));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
		}});
		restarted.signIn(password);
		assertEquals(0, restarted.failedSignInAttempts());
		assertFalse(new File(keyDir, "login.lockout").exists());
	}

	private AccountManagerImpl throttled(
			java.util.concurrent.atomic.AtomicLong mono) {
		return new AccountManagerImpl(databaseConfig, crypto,
				identityManager) {
			@Override
			protected LoginThrottle createLoginThrottle(File stateFile) {
				return new LoginThrottle(LoginThrottle.fileStore(stateFile),
						mono::get, () -> "boot", LoginThrottle.SIGN_IN);
			}
		};
	}

	/** STO-06: both key files are written through synced temporaries and
	 *  atomic renames; no temporary file survives and neither file is
	 *  ever empty. */
	@Test
	public void storingTheKeyLeavesCompleteFilesAndNoTemporaries()
			throws Exception {
		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);
		synchronized (accountManager.stateChangeLock) {
			assertTrue(accountManager.storeEncryptedDatabaseKey(
					newEncryptedKeyHex));
		}
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(newEncryptedKeyHex, loadDatabaseKey(keyBackupFile));
		String[] names = keyDir.list();
		assertNotNull(names);
		for (String n : names) {
			assertFalse(n, n.endsWith(".tmp"));
			assertTrue(n, new File(keyDir, n).length() > 0);
		}
	}

	/** AND-09: a strengthener that fails during the sign-in upgrade leaves
	 *  the stored key as it is instead of writing a password-only key. */
	@Test
	public void strengthenerFailureDuringUpgradeKeepsTheStoredKey()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).encryptWithPassword(key.getBytes(), password,
					keyStrengthener);
			will(throwException(new org.zerionproject.core.api.crypto
					.KeyStrengthenerException(new RuntimeException())));
		}});
		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);
		accountManager.signIn(password);
		assertTrue(accountManager.hasDatabaseKey());
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	/** AND-09: a password change never silently drops the device binding. */
	@Test
	public void changePasswordReportsAStrengthenerFailure() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).decryptWithPassword(encryptedKey, password,
					keyStrengthener);
			will(returnValue(key.getBytes()));
			oneOf(crypto).isEncryptedWithStrengthenedKey(encryptedKey);
			will(returnValue(true));
			oneOf(crypto).isEncryptedWithLegacyKdf(encryptedKey);
			will(returnValue(false));
			oneOf(crypto).encryptWithPassword(key.getBytes(), newPassword,
					keyStrengthener);
			will(throwException(new org.zerionproject.core.api.crypto
					.KeyStrengthenerException(new RuntimeException())));
		}});
		storeDatabaseKey(keyFile, encryptedKeyHex);
		storeDatabaseKey(keyBackupFile, encryptedKeyHex);
		try {
			accountManager.changePassword(password, newPassword);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(org.zerionproject.core.api.crypto.DecryptionResult
					.KEY_STRENGTHENER_ERROR, expected.getDecryptionResult());
		}
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyFile));
		assertEquals(encryptedKeyHex, loadDatabaseKey(keyBackupFile));
	}

	private void storeDatabaseKey(File f, String hex) throws IOException {
		f.getParentFile().mkdirs();
		FileOutputStream out = new FileOutputStream(f);
		out.write(hex.getBytes(UTF_8));
		out.flush();
		out.close();
	}

	@Nullable
	private String loadDatabaseKey(File f) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(f), UTF_8));
		String hex = reader.readLine();
		reader.close();
		return hex;
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void testStoringTheDbKeyLeavesOnlyTheKeyAndItsBackup()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(identityManager).createIdentity(authorName);
			will(returnValue(identity));
			oneOf(identityManager).registerIdentity(identity);
			oneOf(crypto).generateSecretKey();
			will(returnValue(key));
			oneOf(crypto).encryptWithPassword(key.getBytes(), password,
					keyStrengthener);
			will(returnValue(encryptedKey));
		}});

		assertTrue(accountManager.createAccount(authorName, password));

		String[] files = keyDir.list();
		assertNotNull(files);
		java.util.Arrays.sort(files);
		assertArrayEquals(new String[] {"db.key", "db.key.bak"}, files);
	}
}
