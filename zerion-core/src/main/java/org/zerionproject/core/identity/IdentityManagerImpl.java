package org.zerionproject.core.identity;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@ThreadSafe
@NotNullByDefault
class IdentityManagerImpl implements IdentityManager, OpenDatabaseHook {

	private final DatabaseComponent db;
	private final CryptoComponent crypto;
	private final AuthorFactory authorFactory;
	private final Clock clock;

	@Nullable
	private volatile Identity cachedIdentity = null;

	private volatile boolean shouldStoreIdentity = false;

	private volatile boolean shouldStoreKeys = false;

	private volatile boolean shouldStoreHybridKeys = false;

	private volatile boolean shouldStoreMlDsaKeys = false;

	@Inject
	IdentityManagerImpl(DatabaseComponent db, CryptoComponent crypto,
			AuthorFactory authorFactory, Clock clock) {
		this.db = db;
		this.crypto = crypto;
		this.authorFactory = authorFactory;
		this.clock = clock;
	}

	@Override
	public Identity createIdentity(String name) {
		LocalAuthor localAuthor = authorFactory.createLocalAuthor(name);
		KeyPair classicalKeyPair = crypto.generateAgreementKeyPair();
		PublicKey classicalPub = classicalKeyPair.getPublic();
		PrivateKey classicalPriv = classicalKeyPair.getPrivate();
		KeyPair hybridKeyPair = crypto.generateHybridAgreementKeyPair();
		PublicKey hybridPub = hybridKeyPair.getPublic();
		PrivateKey hybridPriv = hybridKeyPair.getPrivate();

		KeyPair hybridSigKeyPair = crypto.generateHybridSignatureKeyPair();
		byte[] mlDsaPub = ((HybridSignaturePublicKey)
				hybridSigKeyPair.getPublic()).getMlDsaPublicKey();
		byte[] mlDsaPriv = ((HybridSignaturePrivateKey)
				hybridSigKeyPair.getPrivate()).getMlDsaPrivateKey();

		return new Identity(localAuthor, classicalPub, classicalPriv,
				hybridPub, hybridPriv, mlDsaPub, mlDsaPriv,
				clock.currentTimeMillis());
	}

	@Override
	public void registerIdentity(Identity i) {
		if (!i.hasHandshakeKeyPair()) throw new IllegalArgumentException();
		cachedIdentity = i;
		shouldStoreIdentity = true;
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Collection<Identity> existing = db.getIdentities(txn);
		if (!existing.isEmpty()) {
			cachedIdentity = existing.iterator().next();
			shouldStoreIdentity = false;
		}
		Identity cached = getCachedIdentity(txn);
		if (shouldStoreIdentity) {
			db.addIdentity(txn, cached);
		} else if (shouldStoreKeys) {
			PublicKey handshakePub =
					requireNonNull(cached.getHandshakePublicKey());
			PrivateKey handshakePriv =
					requireNonNull(cached.getHandshakePrivateKey());
			db.setHandshakeKeyPair(txn, cached.getId(), handshakePub,
					handshakePriv);
		}
		if (shouldStoreHybridKeys && cached.hasHybridHandshakeKeyPair()) {
			PublicKey hybridPub =
					requireNonNull(cached.getHybridHandshakePublicKey());
			PrivateKey hybridPriv =
					requireNonNull(cached.getHybridHandshakePrivateKey());
			db.setHybridHandshakeKeyPair(txn, cached.getId(), hybridPub,
					hybridPriv);
		}
		if (!cached.hasHybridHandshakeKeyPair()) {
			KeyPair hybridKeyPair = crypto.generateHybridAgreementKeyPair();
			PublicKey newHybridPub = hybridKeyPair.getPublic();
			PrivateKey newHybridPriv = hybridKeyPair.getPrivate();
			db.setHybridHandshakeKeyPair(txn, cached.getId(), newHybridPub,
					newHybridPriv);
			cached = new Identity(
					cached.getLocalAuthor(),
					cached.getHandshakePublicKey(),
					cached.getHandshakePrivateKey(),
					newHybridPub, newHybridPriv,
					cached.getMlDsaSigPublicKey(),
					cached.getMlDsaSigPrivateKey(),
					cached.getTimeCreated());
			cachedIdentity = cached;
		}
		if (!cached.hasMlDsaSigKeyPair()) {
			KeyPair hybridSigKeyPair = crypto.generateHybridSignatureKeyPair();
			byte[] mlDsaPub = ((HybridSignaturePublicKey)
					hybridSigKeyPair.getPublic()).getMlDsaPublicKey();
			byte[] mlDsaPriv = ((HybridSignaturePrivateKey)
					hybridSigKeyPair.getPrivate()).getMlDsaPrivateKey();
			db.setMlDsaSigKeyPair(txn, cached.getId(), mlDsaPub, mlDsaPriv);
			cachedIdentity = new Identity(
					cached.getLocalAuthor(),
					cached.getHandshakePublicKey(),
					cached.getHandshakePrivateKey(),
					cached.getHybridHandshakePublicKey(),
					cached.getHybridHandshakePrivateKey(),
					mlDsaPub, mlDsaPriv,
					cached.getTimeCreated());
		}
		if (shouldStoreMlDsaKeys && cached.hasMlDsaSigKeyPair()) {
			byte[] mlDsaPub = requireNonNull(cached.getMlDsaSigPublicKey());
			byte[] mlDsaPriv = requireNonNull(cached.getMlDsaSigPrivateKey());
			db.setMlDsaSigKeyPair(txn, cached.getId(), mlDsaPub, mlDsaPriv);
		}
		cachedIdentity = null;
	}

	@Override
	public LocalAuthor getLocalAuthor() throws DbException {
		Identity cached = cachedIdentity;
		if (cached == null)
			cached = db.transactionWithResult(true, this::getCachedIdentity);
		return cached.getLocalAuthor();
	}

	@Override
	public LocalAuthor getLocalAuthor(Transaction txn) throws DbException {
		return getCachedIdentity(txn).getLocalAuthor();
	}

	@Override
	public KeyPair getHandshakeKeys(Transaction txn) throws DbException {
		Identity cached = getCachedIdentity(txn);
		PublicKey handshakePub = requireNonNull(cached.getHandshakePublicKey());
		PrivateKey handshakePriv =
				requireNonNull(cached.getHandshakePrivateKey());
		return new KeyPair(handshakePub, handshakePriv);
	}

	@Override
	@Nullable
	public KeyPair getHybridHandshakeKeys(Transaction txn) throws DbException {
		Identity cached = getCachedIdentity(txn);
		if (!cached.hasHybridHandshakeKeyPair()) {
			return null;
		}
		PublicKey hybridPub = requireNonNull(cached.getHybridHandshakePublicKey());
		PrivateKey hybridPriv = requireNonNull(cached.getHybridHandshakePrivateKey());
		return new KeyPair(hybridPub, hybridPriv);
	}

	@Override
	@Nullable
	public byte[] getLocalMlDsaSigPublicKey() throws DbException {
		Identity cached = cachedIdentity;
		if (cached == null) {
			cached = db.transactionWithResult(true, this::getCachedIdentity);
		}
		return cached.getMlDsaSigPublicKey();
	}

	@Override
	@Nullable
	public byte[] getLocalMlDsaSigPublicKey(Transaction txn)
			throws DbException {
		return getCachedIdentity(txn).getMlDsaSigPublicKey();
	}

	@Override
	@Nullable
	public byte[] getLocalMlDsaSigPrivateKey() throws DbException {
		Identity cached = cachedIdentity;
		if (cached == null) {
			cached = db.transactionWithResult(true, this::getCachedIdentity);
		}
		return cached.getMlDsaSigPrivateKey();
	}

	@Override
	@Nullable
	public byte[] getLocalMlDsaSigPrivateKey(Transaction txn)
			throws DbException {
		return getCachedIdentity(txn).getMlDsaSigPrivateKey();
	}

	@Override
	public Identity getIdentity(Transaction txn) throws DbException {
		return getCachedIdentity(txn);
	}

	@Override
	public boolean supportsPostQuantum(Transaction txn) throws DbException {
		return getCachedIdentity(txn).supportsPostQuantum();
	}

	private Identity getCachedIdentity(Transaction txn) throws DbException {
		Identity cached = cachedIdentity;
		if (cached == null)
			cachedIdentity = cached = loadIdentityWithKeyPair(txn);
		return cached;
	}

	private Identity loadIdentityWithKeyPair(Transaction txn)
			throws DbException {
		Collection<Identity> identities = db.getIdentities(txn);
		if (identities.size() != 1) throw new DbException();
		Identity i = identities.iterator().next();

		PublicKey classicalPub = i.getHandshakePublicKey();
		PrivateKey classicalPriv = i.getHandshakePrivateKey();
		PublicKey hybridPub = i.getHybridHandshakePublicKey();
		PrivateKey hybridPriv = i.getHybridHandshakePrivateKey();

		if ((classicalPub == null) != (classicalPriv == null)) {
			throw new DbException();
		}
		if ((hybridPub == null) != (hybridPriv == null)) {
			throw new DbException();
		}

		if (!i.hasHandshakeKeyPair()) {
			KeyPair classicalKeyPair = crypto.generateAgreementKeyPair();
			classicalPub = classicalKeyPair.getPublic();
			classicalPriv = classicalKeyPair.getPrivate();
			shouldStoreKeys = true;
		}

		if (!i.hasHybridHandshakeKeyPair()) {
			KeyPair hybridKeyPair = crypto.generateHybridAgreementKeyPair();
			hybridPub = hybridKeyPair.getPublic();
			hybridPriv = hybridKeyPair.getPrivate();
			shouldStoreHybridKeys = true;
		}

		return new Identity(i.getLocalAuthor(),
				classicalPub, classicalPriv,
				hybridPub, hybridPriv,
				i.getMlDsaSigPublicKey(), i.getMlDsaSigPrivateKey(),
				i.getTimeCreated());
	}
}
