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
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.junit.Test;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.junit.Assert.assertEquals;

public class IdentityManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final AuthorFactory authorFactory =
			context.mock(AuthorFactory.class);
	private final Clock clock = context.mock(Clock.class);

	private final Transaction txn = new Transaction(null, false);

	private final Identity identityWithClassicalKeys = TestUtils.getIdentity();
	private final LocalAuthor localAuthor = identityWithClassicalKeys.getLocalAuthor();

	private final PublicKey mlDsaPublicKey = new HybridSignaturePublicKey(
			getRandomBytes(32), getRandomBytes(ML_DSA_65_PUBLIC_KEY_BYTES));
	private final PrivateKey mlDsaPrivateKey = new HybridSignaturePrivateKey(
			getRandomBytes(32), getRandomBytes(ML_DSA_65_PRIVATE_KEY_BYTES));
	private final KeyPair mlDsaKeyPair =
			new KeyPair(mlDsaPublicKey, mlDsaPrivateKey);

	private final IdentityManagerImpl identityManager =
			new IdentityManagerImpl(db, crypto, authorFactory, clock);

	@Test
	public void testOpenDatabaseIdentityRegistered() throws Exception {

		context.checking(new Expectations() {{
			oneOf(db).getIdentities(txn);
			will(returnValue(emptyList()));
			oneOf(db).addIdentity(with(any(Transaction.class)),
					with(any(Identity.class)));
			oneOf(crypto).generateHybridSignatureKeyPair();
			will(returnValue(mlDsaKeyPair));
			oneOf(db).setMlDsaSigKeyPair(with(any(Transaction.class)),
					with(any(AuthorId.class)),
					with(any(byte[].class)), with(any(byte[].class)));
		}});

		identityManager.registerIdentity(identityWithClassicalKeys);
		identityManager.onDatabaseOpened(txn);
	}

	@Test
	public void testGetLocalAuthorIdentityRegistered() throws DbException {
		identityManager.registerIdentity(identityWithClassicalKeys);
		assertEquals(localAuthor, identityManager.getLocalAuthor());
	}

}
