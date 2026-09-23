package org.zerionproject.core.account;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class AndroidAccountManagerTest extends BrambleMockTestCase {

	private final SharedPreferences prefs =
			context.mock(SharedPreferences.class, "prefs");
	private final SharedPreferences defaultPrefs =
			context.mock(SharedPreferences.class, "defaultPrefs");
	private final DatabaseConfig databaseConfig =
			context.mock(DatabaseConfig.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final ProfileManager profileManager;
	private final SharedPreferences.Editor
			editor = context.mock(SharedPreferences.Editor.class);
	private final Application app;
	private final ApplicationInfo applicationInfo;

	private final File testDir = getTestDirectory();
	private final File keyDir = new File(testDir, "key");
	private final File dbDir = new File(testDir, "db");

	private AndroidAccountManager accountManager;

	public AndroidAccountManagerTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		app = context.mock(Application.class);
		profileManager = context.mock(ProfileManager.class);
		applicationInfo = new ApplicationInfo();
		applicationInfo.dataDir = testDir.getAbsolutePath();
	}

	@Before
	public void setUp() {
		context.checking(new Expectations() {{
			allowing(databaseConfig).getDatabaseDirectory();
			will(returnValue(dbDir));
			allowing(databaseConfig).getDatabaseKeyDirectory();
			will(returnValue(keyDir));
			allowing(app).getApplicationContext();
			will(returnValue(app));
		}});
		accountManager = new AndroidAccountManager(databaseConfig, crypto,
				identityManager, prefs, app, profileManager) {
			@Override
			SharedPreferences getDefaultSharedPreferences() {
				return defaultPrefs;
			}

			@Override
			protected LoginThrottle createLoginThrottle(File stateFile) {
				return LoginThrottle.inFile(stateFile, LoginThrottle.SIGN_IN);
			}
		};
	}

	@Test
	public void testImportProfileRejectsEmptyPassword() {
		org.junit.Assert.assertNull(accountManager.importProfile("Alice",
				new char[0], new byte[10], new byte[32]));
		org.junit.Assert.assertNull(accountManager.importProfile("Alice",
				"   ".toCharArray(), new byte[10], new byte[32]));
	}

	/** AND-09: an imported profile is wrapped with the keystore strengthener
	 *  from the start, exactly like a created one. */
	@Test
	public void testImportProfileWrapsTheKeyWithTheStrengthener()
			throws Exception {
		org.zerionproject.core.api.crypto.KeyStrengthener strengthener =
				context.mock(org.zerionproject.core.api.crypto
						.KeyStrengthener.class);
		byte[] dbKey = new byte[32];
		byte[] wrapped = new byte[40];
		char[] password = "secret".toCharArray();
		File profileDb = new File(testDir, "profiles/p1/db");
		context.checking(new Expectations() {{
			oneOf(profileManager).generateProfileId();
			will(returnValue("p1"));
			oneOf(profileManager).createProfileDir("p1");
			will(returnValue(true));
			allowing(profileManager).getActiveProfileId();
			will(returnValue(null));
			allowing(profileManager).setActiveProfileId(
					with(any(String.class)));
			allowing(profileManager).setActiveProfileId(null);
			oneOf(profileManager).getDbDir("p1");
			will(returnValue(profileDb));
			allowing(databaseConfig).getKeyStrengthener();
			will(returnValue(strengthener));
			oneOf(crypto).encryptWithPassword(dbKey, password, strengthener);
			will(returnValue(wrapped));
			oneOf(profileManager).writeDisplayName("p1", "Alice");
			will(returnValue(true));
		}});
		assertTrue(profileDb.mkdirs());
		assertTrue(keyDir.mkdirs());
		org.junit.Assert.assertEquals("p1", accountManager.importProfile(
				"Alice", password, new byte[10], dbKey));
	}

	private static final String HEX = "0102030405060708";

	private void expectSignIn(java.util.List<String> profiles,
			boolean everMultiple, int derivations) throws Exception {
		org.zerionproject.core.api.crypto.KeyStrengthener strengthener =
				context.mock(org.zerionproject.core.api.crypto
						.KeyStrengthener.class);
		byte[] ciphertext = org.zerionproject.core.util.StringUtils
				.fromHexString(HEX);
		char[] password = "pw".toCharArray();
		context.checking(new Expectations() {{
			allowing(profileManager).getLockoutFile();
			will(returnValue(new File(testDir, "login.lockout")));
			allowing(profileManager).listProfileIds();
			will(returnValue(profiles));
			allowing(profileManager).getActiveProfileId();
			will(returnValue(profiles.get(0)));
			allowing(profileManager).readLastActiveProfileId();
			will(returnValue(null));
			allowing(profileManager).setActiveProfileId(
					with(any(String.class)));
			allowing(profileManager).hasEverHadMultipleProfiles();
			will(returnValue(everMultiple));
			allowing(profileManager).readEncryptedMetaFile(
					with(any(String.class)), with(any(String.class)));
			will(returnValue(null));
			oneOf(profileManager).writeLastActiveProfileId(profiles.get(0));
			allowing(databaseConfig).getKeyStrengthener();
			will(returnValue(strengthener));
			exactly(derivations).of(crypto).decryptWithPassword(
					with(equal(ciphertext)), with(equal(password)),
					with(same(strengthener)));
			will(returnValue(new byte[32]));
			allowing(crypto).isEncryptedWithStrengthenedKey(
					with(equal(ciphertext)));
			will(returnValue(true));
			allowing(crypto).isEncryptedWithLegacyKdf(
					with(equal(ciphertext)));
			will(returnValue(false));
		}});
		assertTrue(keyDir.mkdirs());
		java.nio.file.Files.write(new File(keyDir, "db.key").toPath(),
				HEX.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		accountManager.signIn(password);
		assertTrue(accountManager.hasDatabaseKey());
	}

	@Test
	public void testSignInTriesEveryProfileEvenAfterTheFirstMatches()
			throws Exception {
		expectSignIn(java.util.Arrays.asList("a", "b"), true, 2);
	}

	@Test
	public void testSignInIsPaddedOnceASecondProfileEverExisted()
			throws Exception {
		expectSignIn(java.util.Collections.singletonList("a"), true, 2);
	}

	@Test
	public void testSignInIsNotPaddedOnADeviceThatNeverHadTwoProfiles()
			throws Exception {
		expectSignIn(java.util.Collections.singletonList("a"), false, 1);
	}

	@Test
	public void testDeleteAccountClearsSharedPrefsAndDeletesFiles()
			throws Exception {

		File codeCacheDir = new File(testDir, "code_cache");
		File codeCacheFile = new File(codeCacheDir, "file");
		File libDir = new File(testDir, "lib");
		File libFile = new File(libDir, "file");
		File sharedPrefsDir = new File(testDir, "shared_prefs");
		File sharedPrefsFile = new File(sharedPrefsDir, "file");

		File cacheDir = new File(testDir, "cache");
		File cacheFile = new File(cacheDir, "file");

		File potatoDir = new File(testDir, ".potato");
		File potatoFile = new File(potatoDir, "file");
		File filesDir = new File(testDir, "filesDir");
		File externalCacheDir = new File(testDir, "externalCacheDir");
		File externalCacheDir1 = new File(testDir, "externalCacheDir1");
		File externalCacheDir2 = new File(testDir, "externalCacheDir2");
		File externalMediaDir1 = new File(testDir, "externalMediaDir1");
		File externalMediaDir2 = new File(testDir, "externalMediaDir2");

		context.checking(new Expectations() {{
			oneOf(prefs).edit();
			will(returnValue(editor));
			oneOf(editor).clear();
			will(returnValue(editor));
			oneOf(editor).commit();
			will(returnValue(true));
			oneOf(defaultPrefs).edit();
			will(returnValue(editor));
			oneOf(editor).clear();
			will(returnValue(editor));
			oneOf(editor).commit();
			will(returnValue(true));
			allowing(app).getApplicationInfo();
			will(returnValue(applicationInfo));
			oneOf(app).getFilesDir();
			will(returnValue(filesDir));
			oneOf(app).getCacheDir();
			will(returnValue(cacheDir));
			oneOf(app).getExternalCacheDir();
			will(returnValue(externalCacheDir));
			oneOf(app).getExternalCacheDirs();
			will(returnValue(
					new File[] {externalCacheDir1, externalCacheDir2}));
			oneOf(app).getExternalMediaDirs();
			will(returnValue(
					new File[] {externalMediaDir1, externalMediaDir2}));
			oneOf(profileManager).deleteProfileMetadataKey();
			allowing(profileManager).getLockoutFile();
			will(returnValue(new File(testDir, "login.lockout")));
		}});

		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		assertTrue(codeCacheDir.mkdirs());
		assertTrue(codeCacheFile.createNewFile());
		assertTrue(libDir.mkdirs());
		assertTrue(libFile.createNewFile());
		assertTrue(sharedPrefsDir.mkdirs());
		assertTrue(sharedPrefsFile.createNewFile());
		assertTrue(cacheDir.mkdirs());
		assertTrue(cacheFile.createNewFile());
		assertTrue(potatoDir.mkdirs());
		assertTrue(potatoFile.createNewFile());
		assertTrue(filesDir.mkdirs());
		assertTrue(externalCacheDir.mkdirs());
		assertTrue(externalCacheDir1.mkdirs());
		assertTrue(externalCacheDir2.mkdirs());
		assertTrue(externalMediaDir1.mkdirs());
		assertTrue(externalMediaDir2.mkdirs());

		accountManager.deleteAccount();

		assertFalse(dbDir.exists());
		assertFalse(keyDir.exists());
		assertTrue(codeCacheDir.exists());
		assertTrue(codeCacheFile.exists());
		assertTrue(libDir.exists());
		assertTrue(libFile.exists());
		assertTrue(sharedPrefsDir.exists());
		assertTrue(sharedPrefsFile.exists());
		assertTrue(cacheDir.exists());
		assertFalse(cacheFile.exists());
		assertFalse(potatoDir.exists());
		assertFalse(potatoFile.exists());
		assertFalse(filesDir.exists());
		assertFalse(externalCacheDir.exists());
		assertFalse(externalCacheDir1.exists());
		assertFalse(externalCacheDir2.exists());
		assertFalse(externalMediaDir1.exists());
		assertFalse(externalMediaDir2.exists());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}
}
