package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.LockoutStores;
import org.zerionproject.core.account.LoginThrottle;

import java.io.File;

import javax.annotation.Nullable;

@NotNullByDefault
public final class EncryptedLockoutStores implements LockoutStores {

	static final String FILE = "lockout";

	public interface PrefsOpener {

		SharedPreferences open(Context ctx) throws RuntimeException;
	}

	private final Context ctx;
	private final PrefsOpener opener;

	@Nullable
	private volatile SharedPreferences prefs;

	public EncryptedLockoutStores(Context ctx) {
		this(ctx, c -> ZerionEncryptedPrefs.createBootReadable(c, FILE));
	}

	EncryptedLockoutStores(Context ctx, PrefsOpener opener) {
		this.ctx = ctx.getApplicationContext() == null ? ctx
				: ctx.getApplicationContext();
		this.opener = opener;
	}

	@Override
	public LoginThrottle.Store open(File plaintextFile) {
		SharedPreferences p = prefs();
		if (p == null) return LoginThrottle.fileStore(plaintextFile);
		return new EncryptedLockoutStore(p, plaintextFile);
	}

	@Nullable
	private SharedPreferences prefs() {
		SharedPreferences p = prefs;
		if (p != null) return p;
		synchronized (this) {
			p = prefs;
			if (p != null) return p;
			try {
				p = opener.open(ctx);
			} catch (RuntimeException keystoreUnavailable) {
				return null;
			}
			prefs = p;
			return p;
		}
	}
}
