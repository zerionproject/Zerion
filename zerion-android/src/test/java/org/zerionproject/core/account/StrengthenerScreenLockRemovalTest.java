package org.zerionproject.core.account;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.professor.zerion.android.KeyStrengthenerForTests;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.crypto.FastPasswordCryptoForTests;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.crypto.KeyGenerator;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.StringUtils.toHexString;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StrengthenerScreenLockRemovalTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final char[] PASSWORD = "first password 5$Kq".toCharArray();
	private static final char[] NEW_PASSWORD =
			"second password 8%Lw".toCharArray();

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = FastPasswordCryptoForTests.create();
	private KeyStrengthener strengthener = KeyStrengthenerForTests.create();

	private final DatabaseConfig config = new DatabaseConfig() {
		@Override
		public File getDatabaseDirectory() {
			return new File(testDir, "db");
		}

		@Override
		public File getDatabaseKeyDirectory() {
			return new File(testDir, "key");
		}

		@Nullable
		@Override
		public KeyStrengthener getKeyStrengthener() {
			return strengthener;
		}
	};

	@Before
	public void clearKeyStore() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : Collections.list(ks.aliases())) {
			ks.deleteEntry(alias);
		}
	}

	@After
	public void tearDown() throws Exception {
		clearKeyStore();
		deleteTestDirectory(testDir);
	}

	private AccountManagerImpl process() {
		strengthener = KeyStrengthenerForTests.create();
		return new AccountManagerImpl(config, crypto,
				mock(IdentityManager.class));
	}

	private String signIn(char[] password) {
		try {
			process().signIn(password.clone());
			return "opens";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private static List<String> aliases() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		List<String> out = new ArrayList<>(Collections.list(ks.aliases()));
		Collections.sort(out);
		return out;
	}

	@Test
	public void anAccountCreatedNowSurvivesTheRemovalOfTheScreenLock()
			throws Exception {
		AccountManagerImpl m = process();
		m.createAccount("me", PASSWORD.clone());
		String before = signIn(PASSWORD);
		TestAndroidKeyStore.removeScreenLock();
		assertEquals("before opens, after removing the screen lock opens",
				"before " + before + ", after removing the screen lock "
						+ signIn(PASSWORD));
	}

	@Test
	public void anAccountOfAnEarlierVersionIsMovedOffTheScreenLockBoundKey()
			throws Exception {
		KeyGenerator kg = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
		kg.init(new KeyGenParameterSpec.Builder("db",
				KeyProperties.PURPOSE_SIGN)
				.setIsStrongBoxBacked(true)
				.setUnlockedDeviceRequired(true)
				.setKeySize(256)
				.build());
		kg.generateKey();
		KeyStrengthener earlierVersion = new KeyStrengthener() {
			@Override
			public boolean isInitialised() {
				return strengthener.isInitialised();
			}

			@Override
			public SecretKey strengthenKey(SecretKey k) {
				return strengthener.strengthenKey(k);
			}

			@Override
			public void discardKeyBeforeFirstAccount() {
			}
		};
		byte[] dbKey = getRandomBytes(SecretKey.LENGTH);
		String hex = toHexString(crypto.encryptWithPassword(dbKey,
				PASSWORD.clone(), earlierVersion));
		AccountManagerImpl writer = process();
		synchronized (writer.stateChangeLock) {
			writer.storeEncryptedDatabaseKey(hex);
		}

		String first = signIn(PASSWORD);
		boolean oldKeyLeft = aliases().contains("db");
		TestAndroidKeyStore.removeScreenLock();
		assertEquals("first sign-in opens, screen-lock-bound key deleted,"
						+ " after removing the screen lock opens",
				"first sign-in " + first + ", screen-lock-bound key "
						+ (oldKeyLeft ? "kept" : "deleted")
						+ ", after removing the screen lock "
						+ signIn(PASSWORD));
	}

	@Test
	public void anOldCopyOfTheKeyFileStopsOpeningAfterAPasswordChange()
			throws Exception {
		AccountManagerImpl m = process();
		m.createAccount("me", PASSWORD.clone());
		File keyDir = config.getDatabaseKeyDirectory();
		byte[] oldPrimary = Files.readAllBytes(
				new File(keyDir, "db.key").toPath());
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		String newOpens = signIn(NEW_PASSWORD);

		for (String name : new String[] {"db.key", "db.key.bak"}) {
			Files.write(new File(keyDir, name).toPath(), oldPrimary);
		}
		new File(keyDir, "db.key.state").delete();
		assertEquals("new password opens, old copy with old password"
						+ " KEY_STRENGTHENER_ERROR",
				"new password " + newOpens
						+ ", old copy with old password "
						+ signIn(PASSWORD));
	}
}
