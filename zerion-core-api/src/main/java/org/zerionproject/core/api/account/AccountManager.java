package org.zerionproject.core.api.account;

import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.IdentityManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface AccountManager {

	boolean hasDatabaseKey();

	@Nullable
	SecretKey getDatabaseKey();

	boolean accountExists();

	boolean createAccount(String name, char[] password);

	void deleteAccount();

	void signIn(char[] password) throws DecryptionException;

	/**
	 * Milliseconds until the next sign-in attempt is accepted, or zero. The
	 * lockout is kept on a monotonic clock and survives a restart.
	 */
	long signInLockoutRemainingMs();

	/** Consecutive failed sign-in attempts still counted against the account. */
	int failedSignInAttempts();

	void changePassword(char[] oldPassword, char[] newPassword)
			throws DecryptionException;
}
