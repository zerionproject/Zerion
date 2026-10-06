package org.zerionproject.core.account;

import android.app.Application;
import android.content.SharedPreferences;

import org.jmock.Expectations;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.jmock.lib.action.CustomAction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class ProfileGateIsolationTest extends BrambleMockTestCase {

	private final SharedPreferences prefs =
			context.mock(SharedPreferences.class);
	private final DatabaseConfig databaseConfig =
			context.mock(DatabaseConfig.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final KeyStrengthener strengthener;
	private final ProfileManager profileManager;
	private final Application app;

	private final File testDir = getTestDirectory();
	private final AtomicReference<String> active = new AtomicReference<>("a");

	private final byte[] keyA = filled(0x11);
	private final byte[] keyB = filled(0x22);
	private final byte[] ctA = filled(0xA1);
	private final byte[] ctB = filled(0xB2);
	private final char[] pwA = "password-of-a".toCharArray();
	private final char[] pwB = "password-of-b".toCharArray();

	private AndroidAccountManager accountManager;

	public ProfileGateIsolationTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		app = context.mock(Application.class);
		profileManager = context.mock(ProfileManager.class);
		strengthener = context.mock(KeyStrengthener.class);
	}

	private static byte[] filled(int b) {
		byte[] out = new byte[32];
		Arrays.fill(out, (byte) b);
		return out;
	}

	private File keyDir(String profile) {
		return new File(testDir, "key-" + profile);
	}

	@Before
	public void setUp() throws Exception {
		Action activeKeyDir = new CustomAction("key dir of the active profile") {
			@Override
			public Object invoke(Invocation invocation) {
				return keyDir(active.get());
			}
		};
		Action decrypt = new CustomAction("decrypt one profile's key") {
			@Override
			public Object invoke(Invocation invocation) throws Throwable {
				byte[] ct = (byte[]) invocation.getParameter(0);
				char[] pw = (char[]) invocation.getParameter(1);
				if (Arrays.equals(ct, ctA) && Arrays.equals(pw, pwA)) {
					return keyA.clone();
				}
				if (Arrays.equals(ct, ctB) && Arrays.equals(pw, pwB)) {
					return keyB.clone();
				}
				throw new DecryptionException(
						DecryptionResult.INVALID_PASSWORD);
			}
		};
		context.checking(new Expectations() {{
			allowing(databaseConfig).getDatabaseDirectory();
			will(returnValue(new File(testDir, "db")));
			allowing(databaseConfig).getDatabaseKeyDirectory();
			will(activeKeyDir);
			allowing(databaseConfig).getKeyStrengthener();
			will(returnValue(strengthener));
			allowing(strengthener).currentGeneration();
			will(returnValue(org.zerionproject.core.api.crypto
					.KeyStrengthener.LEGACY_GENERATION));
			allowing(strengthener).startNewGeneration();
			will(returnValue(false));
			allowing(profileManager).getAppFilesRoot();
			will(returnValue(new File(testDir, "files")));
			allowing(app).getApplicationContext();
			will(returnValue(app));
			allowing(profileManager).getLockoutFile();
			will(returnValue(new File(testDir, "login.lockout")));
			allowing(profileManager).listProfileIds();
			will(returnValue(Arrays.asList("a", "b")));
			allowing(profileManager).getActiveProfileId();
			will(new CustomAction("active profile") {
				@Override
				public Object invoke(Invocation invocation) {
					return active.get();
				}
			});
			allowing(profileManager).setActiveProfileId(
					with(any(String.class)));
			will(new CustomAction("switch active profile") {
				@Override
				public Object invoke(Invocation invocation) {
					active.set((String) invocation.getParameter(0));
					return null;
				}
			});
			allowing(profileManager).readLastActiveProfileId();
			will(returnValue("a"));
			allowing(profileManager).startSession(with(any(String.class)));
			allowing(profileManager).readEncryptedMetaFile(
					with(any(String.class)), with(any(String.class)));
			will(returnValue(null));
			allowing(profileManager).writeLastActiveProfileId(
					with(any(String.class)));
			allowing(crypto).decryptWithPassword(with(any(byte[].class)),
					with(any(char[].class)), with(same(strengthener)));
			will(decrypt);
			allowing(crypto).isEncryptedWithStrengthenedKey(
					with(any(byte[].class)));
			will(returnValue(true));
			allowing(crypto).isEncryptedWithLegacyKdf(with(any(byte[].class)));
			will(returnValue(false));
		}});
		writeKeyFile("a", ctA);
		writeKeyFile("b", ctB);
		accountManager = new AndroidAccountManager(databaseConfig, crypto,
				identityManager, prefs, app, profileManager) {
			@Override
			protected LoginThrottle createLoginThrottle(File stateFile) {
				return LoginThrottle.inFile(new File(testDir, "login.lockout"),
						LoginThrottle.SIGN_IN);
			}

			@Override
			protected LoginThrottle createPasswordCheckThrottle() {
				return LoginThrottle.inFile(
						new File(testDir, "password.check.lockout"),
						AndroidAccountManager.PASSWORD_CHECK);
			}
		};
		accountManager.signIn(pwA.clone());
		assertEquals("a", active.get());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private void writeKeyFile(String profile, byte[] ciphertext)
			throws Exception {
		File dir = keyDir(profile);
		assertTrue(dir.mkdirs());
		Files.write(new File(dir, "db.key").toPath(),
				toHexString(ciphertext).getBytes(StandardCharsets.UTF_8));
	}

	private byte[] keyFileBytes(String profile) throws Exception {
		return Files.readAllBytes(new File(keyDir(profile), "db.key").toPath());
	}

	@Test
	public void anotherProfilesPasswordIsRefusedAndMovesNothing()
			throws Exception {
		SecretKey loaded = accountManager.getDatabaseKey();
		assertNotNull(loaded);
		byte[] fileA = keyFileBytes("a");
		byte[] fileB = keyFileBytes("b");
		try {
			accountManager.verifyPassword(pwB.clone());
			fail();
		} catch (DecryptionException expected) {
		}
		assertEquals("A is still the active profile", "a", active.get());
		assertSame(loaded, accountManager.getDatabaseKey());
		assertArrayEquals("A's key is intact", keyA, loaded.getBytes());
		assertArrayEquals(fileA, keyFileBytes("a"));
		assertArrayEquals(fileB, keyFileBytes("b"));
		assertEquals(1, accountManager.failedSignInAttempts());
	}

	@Test
	public void theSignedInProfilesPasswordIsAcceptedAndMovesNothing()
			throws Exception {
		SecretKey loaded = accountManager.getDatabaseKey();
		assertNotNull(loaded);
		byte[] fileA = keyFileBytes("a");
		accountManager.verifyPassword(pwA.clone());
		assertEquals("a", active.get());
		assertSame(loaded, accountManager.getDatabaseKey());
		assertArrayEquals(keyA, loaded.getBytes());
		assertArrayEquals(fileA, keyFileBytes("a"));
		assertEquals(0, accountManager.failedSignInAttempts());
	}

	@Test
	public void aWrongPasswordIsCountedAndMovesNothing() throws Exception {
		SecretKey loaded = accountManager.getDatabaseKey();
		try {
			accountManager.verifyPassword("nobody".toCharArray());
			fail();
		} catch (DecryptionException expected) {
		}
		assertEquals("a", active.get());
		assertSame(loaded, accountManager.getDatabaseKey());
		assertEquals(1, accountManager.failedSignInAttempts());
	}

	@Test
	public void deletionRemovesOnlyTheProfileTheUserChose() {
		assertFalse(accountManager.deleteActiveProfile("b"));
		context.checking(new Expectations() {{
			allowing(profileManager).hasKeyFiles("b");
			will(returnValue(true));
			allowing(crypto).encryptWithPassword(with(any(byte[].class)),
					with(any(char[].class)), with(same(strengthener)));
			will(returnValue(new byte[] {1}));
			oneOf(profileManager).shredProfileKeys("a");
			oneOf(profileManager).forgetLastActiveProfileId("a");
		}});
		assertTrue(accountManager.deleteActiveProfile("a"));
	}
}
