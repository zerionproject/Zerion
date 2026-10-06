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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class AndroidKeyFileAlignmentTest extends BrambleMockTestCase {

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
	private final byte[] altered = filled(0xA3);
	private final char[] pwA = "password-of-a".toCharArray();
	private final char[] pwB = "password-of-b".toCharArray();

	public AndroidKeyFileAlignmentTest() {
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
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private void write(String profile, String name, String content)
			throws IOException {
		File dir = keyDir(profile);
		if (!dir.isDirectory()) assertTrue(dir.mkdirs());
		Files.write(new File(dir, name).toPath(),
				content.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String profile, String name) throws IOException {
		return new String(Files.readAllBytes(
				new File(keyDir(profile), name).toPath()),
				StandardCharsets.UTF_8);
	}

	@Test
	public void shreddingRemovesTheKeysOfEveryProfile() throws Exception {
		write("a", "db.key", "aa");
		write("a", "db.key.bak", "aa");
		write("b", "db.key", "bb");
		write("b", "db.key.bak", "bb");
		context.checking(new Expectations() {{
			allowing(profileManager).getKeyDirWithoutCreating("a");
			will(returnValue(keyDir("a")));
			allowing(profileManager).getKeyDirWithoutCreating("b");
			will(returnValue(keyDir("b")));
			oneOf(strengthener).discardKeyBeforeFirstAccount();
		}});

		manager(false).shredDatabaseKey();

		assertTrue("the active profile's key is gone", !keyDir("a").exists());
		assertTrue("the other profile's key is gone", !keyDir("b").exists());
	}

	private AndroidAccountManager manager(boolean primaryWritesFail) {
		return new AndroidAccountManager(databaseConfig, crypto,
				identityManager, prefs, app, profileManager) {
			@Override
			protected LoginThrottle createLoginThrottle(File stateFile) {
				return LoginThrottle.inFile(new File(testDir, "login.lockout"),
						LoginThrottle.SIGN_IN);
			}

			@Override
			protected void writeKeyFile(File f, byte[] bytes)
					throws IOException {
				if (primaryWritesFail && f.getName().equals("db.key")) {
					throw new IOException("injected: primary repair refused");
				}
				super.writeKeyFile(f, bytes);
			}
		};
	}

	@Test
	public void aFailedRepairKeepsTheBackupAndKeyStateThatOpened()
			throws Exception {
		String hexA = toHexString(ctA);
		write("a", "db.key", toHexString(altered));
		write("a", "db.key.bak", hexA);
		write("a", "db.key.state", AccountManagerImpl.keyState(hexA));
		write("b", "db.key", toHexString(ctB));

		AndroidAccountManager first = manager(true);
		first.signIn(pwA.clone());
		assertEquals("a", active.get());
		assertEquals("the repair did not reach the disk",
				toHexString(altered), read("a", "db.key"));
		assertEquals("the backup that opened is kept", hexA,
				read("a", "db.key.bak"));
		assertEquals("the key state that vouches for it is kept",
				AccountManagerImpl.keyState(hexA), read("a", "db.key.state"));

		active.set("b");
		AndroidAccountManager restarted = manager(false);
		restarted.signIn(pwA.clone());
		assertEquals("a", active.get());
		SecretKey key = restarted.getDatabaseKey();
		assertNotNull(key);
		assertArrayEquals(keyA, key.getBytes());
		assertEquals("the next sign-in repairs the primary", hexA,
				read("a", "db.key"));
		assertEquals(0, restarted.failedSignInAttempts());
	}

	@Test
	public void anIntactPrimaryIsStillAlignedAtSignIn() throws Exception {
		String hexA = toHexString(ctA);
		write("a", "db.key", hexA);
		write("a", "db.key.bak", "not a key");
		write("b", "db.key", toHexString(ctB));
		manager(false).signIn(pwA.clone());
		assertEquals(hexA, read("a", "db.key.bak"));
		assertEquals(AccountManagerImpl.keyState(hexA),
				read("a", "db.key.state"));
	}
}
