package org.zerionproject.core.account;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

@NotNullByDefault
public interface LockoutStores {

	LoginThrottle.Store open(File plaintextFile);

	static LockoutStores plaintext() {
		return LoginThrottle::fileStore;
	}
}
