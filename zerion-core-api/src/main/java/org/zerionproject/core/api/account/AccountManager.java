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

	void changePassword(char[] oldPassword, char[] newPassword)
			throws DecryptionException;
}
