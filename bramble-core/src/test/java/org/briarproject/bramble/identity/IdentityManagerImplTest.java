package org.briarproject.bramble.identity;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.Identity;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.TestUtils;
import org.jmock.Expectations;
import org.junit.Test;

import static java.util.Collections.emptyList;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.test.TestUtils.getRandomBytes;
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
