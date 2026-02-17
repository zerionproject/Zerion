package org.briarproject.bramble.api.account;

import org.briarproject.bramble.api.crypto.DecryptionException;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.identity.IdentityManager;
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
