package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.crypto.CryptoExecutor;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
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

	@Nullable
	byte[] getLocalMlDsaSigPublicKey() throws DbException;

	@Nullable
	byte[] getLocalMlDsaSigPublicKey(Transaction txn) throws DbException;

	@Nullable
	byte[] getLocalMlDsaSigPrivateKey() throws DbException;

	@Nullable
	byte[] getLocalMlDsaSigPrivateKey(Transaction txn) throws DbException;

	Identity getIdentity(Transaction txn) throws DbException;

	boolean supportsPostQuantum(Transaction txn) throws DbException;
}
