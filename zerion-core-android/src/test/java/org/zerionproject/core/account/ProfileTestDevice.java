package org.zerionproject.core.account;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.identity.IdentityManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.util.StringUtils.toHexString;

final class ProfileTestDevice {

	private static final AtomicInteger DEVICES = new AtomicInteger();

	final File testDir;
	final File filesDir;
	final AtomicInteger derivations = new AtomicInteger();
	private final Mockery context;
	private final Context appContext;
	private final Application app;
	private final IdentityManager identityManager;
	private final SharedPreferences prefs;
	private final CryptoComponent crypto;
	private final SecureRandom random = new SecureRandom();

	ProfileTestDevice(Mockery context, File testDir) {
		this.context = context;
		this.testDir = testDir;
		this.filesDir = new File(testDir, "files");
		int n = DEVICES.incrementAndGet();
		this.appContext = context.mock(Context.class, "device-context-" + n);
		this.app = context.mock(Application.class, "device-app-" + n);
		this.identityManager = context.mock(IdentityManager.class,
				"device-identity-" + n);
		this.prefs = context.mock(SharedPreferences.class,
				"device-prefs-" + n);
		this.crypto = (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "encryptWithPassword":
							derivations.incrementAndGet();
							return seal((byte[]) args[0], (char[]) args[1]);
						case "decryptWithPassword":
							derivations.incrementAndGet();
							return open((byte[]) args[0], (char[]) args[1]);
						case "isEncryptedWithStrengthenedKey":
							return true;
						case "isEncryptedWithLegacyKdf":
							return false;
						case "generateSecretKey":
							byte[] k = new byte[SecretKey.LENGTH];
							random.nextBytes(k);
							return new SecretKey(k);
						default:
							throw new UnsupportedOperationException(
									method.getName());
					}
				});
		context.checking(new Expectations() {{
			allowing(appContext).getFilesDir();
			will(returnValue(filesDir));
			allowing(appContext).getDir(with(any(String.class)),
					with(any(int.class)));
			will(new CustomAction("app dir") {
				@Override
				public Object invoke(Invocation invocation) {
					File d = new File(testDir,
							"app_" + invocation.getParameter(0));
					d.mkdirs();
					return d;
				}
			});
			allowing(app).getApplicationContext();
			will(returnValue(app));
			allowing(identityManager).createIdentity(with(any(String.class)));
			allowing(identityManager).registerIdentity(with(any(
					org.zerionproject.core.api.identity.Identity.class)));
		}});
		new File(filesDir, "profiles").mkdirs();
	}

	private static byte[] tag(char[] password) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return Arrays.copyOf(md.digest(new String(password)
					.getBytes(StandardCharsets.UTF_8)), 8);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] seal(byte[] plaintext, char[] password) {
		byte[] t = tag(password);
		byte[] out = new byte[t.length + plaintext.length];
		System.arraycopy(t, 0, out, 0, t.length);
		System.arraycopy(plaintext, 0, out, t.length, plaintext.length);
		return out;
	}

	private static byte[] open(byte[] ciphertext, char[] password)
			throws DecryptionException {
		byte[] t = tag(password);
		if (ciphertext.length < t.length || !Arrays.equals(t,
				Arrays.copyOf(ciphertext, t.length))) {
			throw new DecryptionException(INVALID_PASSWORD);
		}
		return Arrays.copyOfRange(ciphertext, t.length, ciphertext.length);
	}

	ProfileManager newProfileManager() {
		return new PlainMetadataProfileManager(appContext);
	}

	AndroidAccountManager newAccountManager() {
		return newAccountManager(newProfileManager());
	}

	AndroidAccountManager newAccountManager(ProfileManager profiles) {
		return newAccountManager(profiles, null);
	}

	AndroidAccountManager newAccountManager(ProfileManager profiles,
			@Nullable java.util.function.LongSupplier clock) {
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
			@Nullable
			public KeyStrengthener getKeyStrengthener() {
				return null;
			}
		};
		return new AndroidAccountManager(config, crypto, identityManager,
				prefs, app, profiles) {
			@Override
			protected LoginThrottle createLoginThrottle(File ignored) {
				if (clock == null) {
					return LoginThrottle.inFile(profiles.getLockoutFile(),
							LoginThrottle.SIGN_IN);
				}
				return new LoginThrottle(
						LoginThrottle.fileStore(profiles.getLockoutFile()),
						clock, () -> "boot", LoginThrottle.SIGN_IN);
			}

			@Override
			protected LoginThrottle createPasswordCheckThrottle() {
				return LoginThrottle.inFile(
						profiles.getPasswordCheckLockoutFile(),
						AndroidAccountManager.PASSWORD_CHECK);
			}
		};
	}

	void addProfile(String id, String password) throws IOException {
		File keyDir = new File(new File(filesDir, "profiles/" + id), "key");
		if (!keyDir.isDirectory() && !keyDir.mkdirs()) {
			throw new IOException("key dir");
		}
		new File(new File(filesDir, "profiles/" + id), "db").mkdirs();
		byte[] key = new byte[SecretKey.LENGTH];
		random.nextBytes(key);
		String hex = toHexString(seal(key, password.toCharArray()));
		byte[] bytes = hex.getBytes(StandardCharsets.UTF_8);
		Files.write(new File(keyDir, "db.key").toPath(), bytes);
		Files.write(new File(keyDir, "db.key.bak").toPath(), bytes);
		Files.write(new File(keyDir, "db.key.state").toPath(),
				AccountManagerImpl.keyState(hex)
						.getBytes(StandardCharsets.UTF_8));
	}

	File keyFile(String id) {
		return new File(filesDir, "profiles/" + id + "/key/db.key");
	}

	File profileDir(String id) {
		return new File(filesDir, "profiles/" + id);
	}

	private static final class PlainMetadataProfileManager
			extends ProfileManager {

		PlainMetadataProfileManager(Context appContext) {
			super(appContext);
		}

		private static void write(File f, String value) throws IOException {
			Files.write(f.toPath(), value.getBytes(StandardCharsets.UTF_8));
		}

		@Nullable
		private static String read(File f) {
			if (!f.isFile()) return null;
			try {
				return new String(Files.readAllBytes(f.toPath()),
						StandardCharsets.UTF_8);
			} catch (IOException e) {
				return null;
			}
		}

		@Override
		public boolean writeDisplayName(String profileId, String name) {
			if (!profileExists(profileId)) return false;
			getKeyDir(profileId);
			try {
				write(getDisplayNameFile(profileId), name);
				return true;
			} catch (IOException e) {
				return false;
			}
		}

		@Override
		@Nullable
		public String readDisplayName(String profileId) {
			return read(getDisplayNameFile(profileId));
		}

		@Override
		public void writeEncryptedMetaFile(String profileId, String fileName,
				String value) throws IOException {
			if (!profileExists(profileId)) throw new IOException("profile");
			write(new File(getKeyDir(profileId), fileName), value);
		}

		@Override
		@Nullable
		public String readEncryptedMetaFile(String profileId,
				String fileName) {
			return read(new File(getKeyDirWithoutCreating(profileId),
					fileName));
		}

		@Override
		@Nullable
		public String readLastActiveProfileId() {
			return read(new File(getAppFilesRoot(), "last_active_profile"));
		}

		@Override
		public void writeLastActiveProfileId(String profileId) {
			try {
				write(new File(getAppFilesRoot(), "last_active_profile"),
						profileId);
			} catch (IOException ignored) {
			}
		}
	}
}
