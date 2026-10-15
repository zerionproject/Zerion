package com.professor.zerion.android.vault.wallet.btc;

import android.util.Base64;

import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.function.LongSupplier;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class WalletCredential {

	private static final String KEY_AUTH_FAILURES = "authFailures";
	private static final int SALT_BYTES = 16;
	private static final int ITERATIONS = 120_000;
	private static final int HASH_BYTES = 32;

	private final BtcWalletSettings settings;
	private final WalletCredentialThrottle throttle;
	private volatile boolean failuresRestored = false;

	public WalletCredential(BtcWalletSettings settings,
			LongSupplier monotonicClock) {
		this.settings = settings;
		this.throttle = new WalletCredentialThrottle(monotonicClock);
	}

	public boolean exists() {
		boolean hasAuth = false;
		try {
			JSONObject o = settings.readIfPresent();
			hasAuth = o != null && o.has("authHash");
		} catch (Throwable ignored) {
		}
		return hasAuth;
	}

	public void setup(String type, char[] credential) throws Exception {
		byte[] salt = new byte[SALT_BYTES];
		new SecureRandom().nextBytes(salt);
		int iter = ITERATIONS;
		byte[] hash = pbkdf2(credential, salt, iter, HASH_BYTES);
		synchronized (settings.lock()) {
			JSONObject o = settings.read();
			o.put("authType", type);
			o.put("authSalt", Base64.encodeToString(salt, Base64.NO_WRAP));
			o.put("authHash", Base64.encodeToString(hash, Base64.NO_WRAP));
			o.put("authIter", iter);
			settings.write(o);
		}
	}

	public boolean isPin() {
		try {
			synchronized (settings.lock()) {
				return "PIN".equals(settings.read().optString("authType"));
			}
		} catch (Throwable e) {
			return false;
		}
	}

	public void restoreFailures() {
		if (failuresRestored) return;
		try {
			throttle.restoreFailures(
					settings.readQuiet().optInt(KEY_AUTH_FAILURES, 0));
		} catch (Throwable ignored) {
		}
		failuresRestored = true;
	}

	public boolean isThrottled() {
		return throttle.isThrottled();
	}

	public boolean matches(char[] credential) {
		boolean ok = false;
		try {
			JSONObject o = settings.readQuiet();
			byte[] salt = Base64.decode(o.getString("authSalt"),
					Base64.NO_WRAP);
			int iter = o.getInt("authIter");
			byte[] expected = Base64.decode(o.getString("authHash"),
					Base64.NO_WRAP);
			byte[] hash = pbkdf2(credential, salt, iter, expected.length);
			ok = MessageDigest.isEqual(hash, expected);
		} catch (Throwable ignored) {
		} finally {
			SecureMemory.shred(credential);
		}
		return ok;
	}

	public void recordSuccess() {
		throttle.recordSuccess();
		persistFailures(0);
	}

	public boolean recordFailure() {
		boolean relock = throttle.recordFailure();
		persistFailures(throttle.failures());
		return relock;
	}

	private void persistFailures(int failures) {
		try {
			synchronized (settings.lock()) {
				JSONObject o = settings.readQuiet();
				if (o.optInt(KEY_AUTH_FAILURES, 0) != failures) {
					o.put(KEY_AUTH_FAILURES, failures);
					settings.writeQuiet(o);
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static byte[] pbkdf2(char[] pw, byte[] salt, int iter, int len)
			throws Exception {
		PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, len * 8);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
					.generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}
}
