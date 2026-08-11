package org.zerionproject.app.introduction;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.app.api.client.SessionId;
import org.jmock.Expectations;
import org.junit.Test;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.app.api.introduction.IntroductionConstants.LABEL_SESSION_ID;
import static org.junit.Assert.assertEquals;

public class IntroductionCryptoTest extends BrambleMockTestCase {

	private final CryptoComponent cryptoComponent =
			context.mock(CryptoComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final MlKemProvider mlKemProvider =
			context.mock(MlKemProvider.class);

	private final IntroductionCrypto crypto =
			new IntroductionCryptoImpl(cryptoComponent, clientHelper,
					mlKemProvider);

	private final Author introducer = getAuthor();
	private final Author alice = getAuthor(), bob = getAuthor();
	private final byte[] hash = getRandomId();

	@Test
	public void testGetSessionId() {
		boolean isAlice = crypto.isAlice(alice.getId(), bob.getId());
		context.checking(new Expectations() {{
			oneOf(cryptoComponent).hash(
					LABEL_SESSION_ID,
					introducer.getId().getBytes(),
					isAlice ? alice.getId().getBytes() : bob.getId().getBytes(),
					isAlice ? bob.getId().getBytes() : alice.getId().getBytes()
			);
			will(returnValue(hash));
		}});
		SessionId sessionId = crypto.getSessionId(introducer, alice, bob);
		assertEquals(new SessionId(hash), sessionId);
	}

}
