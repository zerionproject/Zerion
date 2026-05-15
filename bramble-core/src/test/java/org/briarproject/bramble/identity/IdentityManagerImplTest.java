package org.briarproject.bramble.identity;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorFactory;
import org.briarproject.bramble.api.identity.Identity;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.bramble.test.TestUtils;
import org.jmock.Expectations;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class IdentityManagerImplTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final AuthorFactory authorFactory =
			context.mock(AuthorFactory.class);
	private final Clock clock = context.mock(Clock.class);

	private final Transaction txn = new Transaction(null, false);

	private final PublicKey handshakePublicKey = TestUtils.getAgreementPublicKey();
	private final PrivateKey handshakePrivateKey = TestUtils.getAgreementPrivateKey();
	private final KeyPair handshakeKeyPair =
			new KeyPair(handshakePublicKey, handshakePrivateKey);

	private final PublicKey hybridPublicKey = TestUtils.getAgreementPublicKey();
	private final PrivateKey hybridPrivateKey = TestUtils.getAgreementPrivateKey();
	private final KeyPair hybridKeyPair =
			new KeyPair(hybridPublicKey, hybridPrivateKey);

	private final Identity identityWithClassicalKeys = TestUtils.getIdentity();
	private final LocalAuthor localAuthor = identityWithClassicalKeys.getLocalAuthor();

	private final Identity identityWithoutKeys = new Identity(localAuthor,
			null, null, identityWithClassicalKeys.getTimeCreated());

	private final IdentityManagerImpl identityManager =
			new IdentityManagerImpl(db, crypto, authorFactory, clock);

	@Test
	public void testOpenDatabaseIdentityRegistered() throws Exception {

		context.checking(new Expectations() {{
			oneOf(db).addIdentity(with(any(Transaction.class)), with(any(Identity.class)));
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
