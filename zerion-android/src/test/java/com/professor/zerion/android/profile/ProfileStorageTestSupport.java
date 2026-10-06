package com.professor.zerion.android.profile;

import android.content.Context;
import android.content.SharedPreferences;

import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ProfileStorageTestSupport {

	static final String SECURE = "profile-storage-test-secure";
	static final String UI = "profile-storage-test-ui";

	private ProfileStorageTestSupport() {
	}

	static CryptoComponent crypto() {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "deriveKey":
							return new SecretKey(sha256(
									((String) args[0]).getBytes(
											StandardCharsets.UTF_8),
									((SecretKey) args[1]).getBytes()));
						case "hash":
							byte[][] inputs = (byte[][]) args[1];
							byte[][] all = Arrays.copyOf(new byte[][] {
									((String) args[0]).getBytes(
											StandardCharsets.UTF_8)},
									1 + inputs.length);
							System.arraycopy(inputs, 0, all, 1,
									inputs.length);
							return sha256(all);
						default:
							throw new UnsupportedOperationException(
									method.getName());
					}
				});
	}

	static byte[] sha256(byte[]... parts) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			for (byte[] p : parts) md.update(p);
			return md.digest();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	static SecretKey keyOf(String profileId) {
		return new SecretKey(sha256(profileId.getBytes(
				StandardCharsets.UTF_8)));
	}

	static SharedPreferences deviceSecure(Context app) {
		return app.getSharedPreferences(SECURE, Context.MODE_PRIVATE);
	}

	static SharedPreferences deviceUi(Context app) {
		return app.getSharedPreferences(UI, Context.MODE_PRIVATE);
	}

	static void addProfile(Context app, String profileId) throws IOException {
		File key = new File(app.getFilesDir(), "profiles/" + profileId
				+ "/key");
		if (!key.isDirectory() && !key.mkdirs()) throw new IOException();
		Files.write(new File(key, "db.key").toPath(),
				"00".getBytes(StandardCharsets.UTF_8));
	}

	static File profileDir(Context app, String profileId) {
		return new File(app.getFilesDir(), "profiles/" + profileId);
	}

	static ProfileStorage signedIn(Context app, String profileId) {
		ProfileManager profiles = new ProfileManager(app);
		ProfileStorage storage = notSignedIn(app, profiles, profileId);
		profiles.setSessionListener(storage);
		profiles.startSession(profileId);
		return storage;
	}

	static ProfileStorage notSignedIn(Context app) {
		return notSignedIn(app, new ProfileManager(app), null);
	}

	private static ProfileStorage notSignedIn(Context app,
			ProfileManager profiles, String profileId) {
		AccountManager accountManager = mock(AccountManager.class);
		when(accountManager.getDatabaseKey()).thenReturn(
				profileId == null ? null : keyOf(profileId));
		return new ProfileStorage(app, profiles, accountManager, crypto(),
				deviceSecure(app), deviceUi(app), Runnable::run);
	}
}
