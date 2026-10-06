package org.zerionproject.core.account;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import com.professor.zerion.android.testing.Inert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AndroidKeyFileRecoveryDeviceTest {

	private static final String OLD = "old password 7!Ab";
	private static final String NEW = "new password 9#Cd";

	private File root;
	private CryptoComponent crypto;
	private final byte[] strengthenerSecret = new byte[32];
	private String profile;
	private byte[] dbKey;

	@Before
	public void setUp() throws Exception {
		Context base = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		root = new File(base.getCacheDir(),
				"keyfiles-" + System.nanoTime());
		assertTrue(root.mkdirs());
		crypto = "low".equals(InstrumentationRegistry.getArguments()
				.getString("kdf")) ? PasswordCryptoForTests.createLowCost()
				: PasswordCryptoForTests.create();
		new java.security.SecureRandom().nextBytes(strengthenerSecret);
		profile = manager().scheduleProfileCreation("Ann", OLD.toCharArray());
		assertNotNull(profile);
		AndroidAccountManager m = manager();
		m.signIn(OLD.toCharArray());
		SecretKey k = m.getDatabaseKey();
		assertNotNull(k);
		dbKey = k.getBytes().clone();
	}

	@After
	public void tearDown() {
		deleteRecursively(root);
	}

	@Test
	public void aNewProfileHasAlignedKeyFilesAndSignsIn() throws Exception {
		expect("unlocks (failed 0); primary=old backup=old state=primary",
				attempt(OLD));
	}

	@Test
	public void aMalformedOrMissingPrimaryIsRepairedFromTheProvenBackup()
			throws Exception {
		String old = read("db.key");
		write("db.key", "ff" + old.substring(2));
		expect("INVALID_CIPHERTEXT (failed 1); primary=other backup=old"
				+ " state=backup", attempt(NEW));
		expect("unlocks (failed 0); primary=old backup=old state=primary",
				attempt(OLD));
		assertTrue(file("db.key").delete());
		expect("unlocks (failed 0); primary=old backup=old state=primary",
				attempt(OLD));
	}

	@Test
	public void aBackupWithoutAKeyStateIsNotUsed() throws Exception {
		assertTrue(file("db.key").delete());
		assertTrue(file("db.key.state").delete());
		expect("KEY_FILES_DAMAGED (failed 0); primary=missing backup=old"
				+ " state=missing", attempt(OLD));
	}

	@Test
	public void damageToBothFilesFailsClosed() throws Exception {
		write("db.key", "not a key");
		write("db.key.bak", "not a key either");
		expect("KEY_FILES_DAMAGED (failed 0); primary=other backup=other"
				+ " state=other", attempt(OLD));
	}

	@Test
	public void aDamagedBackupIsRealignedAtSignIn() throws Exception {
		write("db.key.bak", "not a key");
		assertTrue(file("db.key.state").delete());
		expect("unlocks (failed 0); primary=old backup=old state=primary",
				attempt(OLD));
	}

	@Test
	public void aVouchedBackupOpensOnlyWithItsOwnPassword() throws Exception {
		String oldHex = read("db.key");
		changePassword();
		String newHex = read("db.key");
		write("db.key.bak", oldHex);
		write("db.key.state", AccountManagerImpl.keyState(oldHex));
		expect("unlocks (failed 0); primary=old backup=old state=primary",
				attempt(OLD));
		expect("INVALID_CIPHERTEXT (failed 1); primary=old backup=old"
				+ " state=primary", attempt(NEW));
		write("db.key", "ff" + newHex.substring(2));
		write("db.key.bak", oldHex);
		write("db.key.state", AccountManagerImpl.keyState(newHex));
		expect("KEY_FILES_DAMAGED (failed 1); primary=other backup=old"
				+ " state=other", attempt(OLD));
		write("db.key", oldHex);
		write("db.key.bak", newHex);
		write("db.key.state", AccountManagerImpl.keyState(newHex));
		expect("unlocks (failed 0); primary=new backup=new state=primary",
				attempt(NEW));
		expect("INVALID_CIPHERTEXT (failed 1); primary=new backup=new"
				+ " state=primary", attempt(OLD));
	}

	@Test
	public void damageIsReportedOnlyForTheProfileLastUsed() throws Exception {
		String other = manager().scheduleProfileCreation("Bo",
				"another password 3$Ef".toCharArray());
		assertNotNull(other);
		AndroidAccountManager m = manager();
		m.signIn(OLD.toCharArray());
		File otherPrimary = new File(new ProfileManager(context())
				.getKeyDir(other), "db.key");
		File otherState = new File(otherPrimary.getParentFile(),
				"db.key.state");
		writeFile(otherPrimary, "not a key");
		assertTrue(otherState.delete());
		assertEquals("damage to a profile not last used stays hidden",
				"INVALID_CIPHERTEXT", result("wrong password 1#Aa"));
		write("db.key", "not a key");
		assertTrue(file("db.key.state").delete());
		assertEquals("damage to the profile last used is reported",
				"KEY_FILES_DAMAGED", result(OLD));
	}

	private void changePassword() throws Exception {
		AndroidAccountManager m = manager();
		m.signIn(OLD.toCharArray());
		m.changePassword(OLD.toCharArray(), NEW.toCharArray());
	}

	private String result(String password) {
		try {
			manager().signIn(password.toCharArray());
			return "unlocks";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private String attempt(String password) throws Exception {
		AndroidAccountManager m = manager();
		String outcome;
		try {
			m.signIn(password.toCharArray());
			SecretKey k = m.getDatabaseKey();
			outcome = k != null && Arrays.equals(dbKey, k.getBytes())
					? "unlocks" : "unlocks a different key";
		} catch (DecryptionException e) {
			outcome = e.getDecryptionResult().name();
		}
		return outcome + " (failed " + m.failedSignInAttempts() + "); "
				+ files() + profilesSeen();
	}

	private String profilesSeen() {
		java.util.List<String> ids = new ProfileManager(context())
				.listProfileIds();
		return ids.size() == 1 ? "" : " profiles=" + ids;
	}

	private String files() throws Exception {
		String p = read("db.key");
		String b = read("db.key.bak");
		String s = read("db.key.state");
		return "primary=" + classify(p) + " backup=" + classify(b)
				+ " state=" + (s == null ? "missing"
				: p != null && s.equals(AccountManagerImpl.keyState(p))
				? "primary" : b != null
				&& s.equals(AccountManagerImpl.keyState(b)) ? "backup"
				: "other");
	}

	private String classify(@Nullable String hex) {
		if (hex == null) return "missing";
		for (String password : new String[] {OLD, NEW}) {
			try {
				byte[] plain = crypto.decryptWithPassword(
						org.zerionproject.core.util.StringUtils
								.fromHexString(hex),
						password.toCharArray(), strengthener());
				if (Arrays.equals(dbKey, plain)) {
					return password.equals(OLD) ? "old" : "new";
				}
			} catch (Exception ignored) {
			}
		}
		return "other";
	}

	private static void expect(String expected, String observed) {
		assertEquals("observed: " + observed, expected, observed);
	}

	private AndroidAccountManager manager() {
		Context ctx = context();
		ProfileManager profiles = new ProfileManager(ctx);
		if (profile != null) profiles.setActiveProfileId(profile);
		DatabaseConfig config = new DatabaseConfig() {
			@Override
			public File getDatabaseDirectory() {
				return profiles.getActiveDbDir();
			}

			@Override
			public File getDatabaseKeyDirectory() {
				return profiles.getActiveKeyDir();
			}

			@Override
			public KeyStrengthener getKeyStrengthener() {
				return strengthener();
			}
		};
		return new AndroidAccountManager(config, crypto,
				Inert.of(IdentityManager.class),
				Inert.of(SharedPreferences.class),
				(Application) InstrumentationRegistry.getInstrumentation()
						.getTargetContext().getApplicationContext(),
				profiles);
	}

	private Context context() {
		Context base = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		File dir = root;
		return new ContextWrapper(base) {
			@Override
			public File getFilesDir() {
				return dir;
			}

			@Override
			public File getDir(String name, int mode) {
				File d = new File(dir, "app_" + name);
				d.mkdirs();
				return d;
			}

			@Override
			public Context getApplicationContext() {
				return this;
			}
		};
	}

	private KeyStrengthener strengthener() {
		byte[] secret = strengthenerSecret;
		return new KeyStrengthener() {
			@Override
			public boolean isInitialised() {
				return true;
			}

			@Override
			public SecretKey strengthenKey(SecretKey k) {
				try {
					Mac mac = Mac.getInstance("HmacSHA256");
					mac.init(new SecretKeySpec(secret, "HmacSHA256"));
					return new SecretKey(mac.doFinal(k.getBytes()));
				} catch (java.security.GeneralSecurityException e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public void discardKeyBeforeFirstAccount() {
			}
		};
	}

	private File file(String name) {
		return new File(new ProfileManager(context()).getKeyDir(profile),
				name);
	}

	@Nullable
	private String read(String name) throws IOException {
		File f = file(name);
		if (!f.isFile()) return null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				new FileInputStream(f), StandardCharsets.UTF_8))) {
			return r.readLine();
		}
	}

	private void write(String name, String content) throws IOException {
		writeFile(file(name), content);
	}

	private static void writeFile(File f, String content) throws IOException {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(content.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void deleteRecursively(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}
}
