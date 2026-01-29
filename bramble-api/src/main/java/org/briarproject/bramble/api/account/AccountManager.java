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

	
	boolean createAccount(String name, String password);

	
	void deleteAccount();

	
	void signIn(String password) throws DecryptionException;

	
	void changePassword(String oldPassword, String newPassword)
			throws DecryptionException;
}
