package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;


@NotNullByDefault
public interface IdentityManager {

	
	@CryptoExecutor
	Identity createIdentity(String name);

	
	void registerIdentity(Identity i);

	
	LocalAuthor getLocalAuthor() throws DbException;

	
	LocalAuthor getLocalAuthor(Transaction txn) throws DbException;

	
	KeyPair getHandshakeKeys(Transaction txn) throws DbException;

	
	@Nullable
	KeyPair getHybridHandshakeKeys(Transaction txn) throws DbException;

	
	Identity getIdentity(Transaction txn) throws DbException;

	
	boolean supportsPostQuantum(Transaction txn) throws DbException;
}
