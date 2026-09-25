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

	/**
	 * Why the last {@link #createAccount} returned false: the class name of
	 * the exception that stopped it, or a short description of the failed
	 * step, so the user can report it. Null after a successful creation.
	 */
	@Nullable
	String getLastCreateAccountError();

	void deleteAccount();

	/**
	 * Removes the encrypted database key files of the active account and
	 * forgets the loaded key, leaving the database unreadable even to the
	 * holder of the password, while the rest of the account is still being
	 * shut down and deleted. A panic purge calls this first so the window
	 * between the trigger and the end of the graceful shutdown holds no
	 * usable copy of the data.
	 */
	void shredDatabaseKey();

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
