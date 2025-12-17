package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.crypto.CryptoExecutor;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Manages the local user's identity, including both classical and
 * post-quantum (hybrid) handshake keys for version negotiation.
 * <p>
 * The dual-key design enables:
 * <ul>
 *   <li>Zerion-to-Zerion: Uses hybrid PQ keys (version 1 links)</li>
 *   <li>Zerion-to-Briar: Uses classical X25519 keys (version 0 links)</li>
 * </ul>
 */
@NotNullByDefault
public interface IdentityManager {

	/**
	 * Creates an identity with the given name. The identity includes both
	 * classical (X25519) and hybrid (X25519 + ML-KEM-768) handshake key pairs
	 * for version negotiation.
	 */
	@CryptoExecutor
	Identity createIdentity(String name);

	/**
	 * Registers the given identity with the manager. This method should be
	 * called before {@link LifecycleManager#startServices(SecretKey)}. The
	 * identity is stored when {@link LifecycleManager#startServices(SecretKey)}
	 * is called. The identity must include at least classical handshake keys.
	 */
	void registerIdentity(Identity i);

	/**
	 * Returns the cached local identity or loads it from the database.
	 */
	LocalAuthor getLocalAuthor() throws DbException;

	/**
	 * Returns the cached local identity or loads it from the database.
	 * <p/>
	 * Read-only.
	 */
	LocalAuthor getLocalAuthor(Transaction txn) throws DbException;

	/**
	 * Returns the cached classical (X25519) handshake keys or loads them
	 * from the database. Use these for Briar-compatible (version 0) links.
	 * <p/>
	 * Read-only.
	 */
	KeyPair getHandshakeKeys(Transaction txn) throws DbException;

	/**
	 * Returns the cached hybrid (PQ) handshake keys or loads them from
	 * the database. Use these for Zerion-to-Zerion (version 1) links.
	 * Returns null if hybrid keys are not available (legacy identity).
	 * <p/>
	 * Read-only.
	 */
	@Nullable
	KeyPair getHybridHandshakeKeys(Transaction txn) throws DbException;

	/**
	 * Returns the full cached identity with all keys, or loads from database.
	 * <p/>
	 * Read-only.
	 */
	Identity getIdentity(Transaction txn) throws DbException;

	/**
	 * Returns true if the local identity supports post-quantum cryptography.
	 * <p/>
	 * Read-only.
	 */
	boolean supportsPostQuantum(Transaction txn) throws DbException;
}
