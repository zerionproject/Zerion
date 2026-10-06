package org.zerionproject.core.account;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

import javax.annotation.Nullable;

import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

abstract class PasswordChangeDeviceFixture {

	static final class SimulatedProcessDeath extends Error {
		SimulatedProcessDeath() {
			super("injected process death");
		}
	}

	static final String OLD = "old password 7!Ab";
	static final String NEW = "new password 9#Cd";

	File testDir;
	DatabaseConfig config;
	CryptoComponent crypto;
	byte[] keyBytes;
	String oldHex;

	@Before
	public void createAccount() throws Exception {
		testDir = new File(InstrumentationRegistry.getInstrumentation()
				.getTargetContext().getCacheDir(),
				"password-change-" + System.nanoTime());
		File dir = testDir;
		config = new DatabaseConfig() {
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
				return null;
			}
		};
		crypto = "low".equals(InstrumentationRegistry.getArguments()
				.getString("kdf")) ? PasswordCryptoForTests.createLowCost()
				: PasswordCryptoForTests.create();
		keyBytes = getRandomBytes(SecretKey.LENGTH);
		oldHex = toHexString(crypto.encryptWithPassword(keyBytes.clone(),
				OLD.toCharArray(), null));
		File keyDir = config.getDatabaseKeyDirectory();
		assertTrue(keyDir.mkdirs());
		writeRaw(new File(keyDir, "db.key"), oldHex);
		writeRaw(new File(keyDir, "db.key.bak"), oldHex);
	}

	@After
	public void deleteAccount() {
		deleteRecursively(testDir);
	}

	private static void deleteRecursively(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) deleteRecursively(c);
		}
		f.delete();
	}

	static void expect(String expected, String observed) {
		assertEquals("observed: " + observed, expected, observed);
	}

	AccountManagerImpl manager() {
		return new AccountManagerImpl(config, crypto, null);
	}

	static String change(AccountManagerImpl m) {
		try {
			m.changePassword(OLD.toCharArray(), NEW.toCharArray());
			return "SUCCESS";
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		} catch (RuntimeException e) {
			return "EXCEPTION " + e.getClass().getSimpleName();
		}
	}

	String files() {
		return "primary=" + classify("db.key") + " backup="
				+ classify("db.key.bak");
	}

	String restart() {
		return "old " + unlocks(OLD) + ", new " + unlocks(NEW) + "; "
				+ files();
	}

	private String unlocks(String password) {
		AccountManagerImpl restarted = manager();
		try {
			restarted.signIn(password.toCharArray());
		} catch (DecryptionException e) {
			return "rejected";
		}
		SecretKey key = restarted.getDatabaseKey();
		if (key == null || !Arrays.equals(keyBytes, key.getBytes())) {
			return "unlocks a different key";
		}
		return "unlocks";
	}

	String classify(String name) {
		String hex = readRaw(new File(config.getDatabaseKeyDirectory(),
				name));
		if (hex == null) return "missing";
		if (hex.equals(oldHex)) return "old";
		try {
			byte[] plain = crypto.decryptWithPassword(fromHexString(hex),
					NEW.toCharArray(), null);
			if (Arrays.equals(keyBytes, plain)) return "new";
		} catch (Exception e) {
			return "other";
		}
		return "other";
	}

	File keyFile(String name) {
		return new File(config.getDatabaseKeyDirectory(), name);
	}

	static void writeRaw(File f, String content) throws IOException {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(content.getBytes(UTF_8));
		}
	}

	@Nullable
	static String readRaw(File f) {
		if (!f.isFile()) return null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				new FileInputStream(f), UTF_8))) {
			return r.readLine();
		} catch (IOException e) {
			return null;
		}
	}
}
