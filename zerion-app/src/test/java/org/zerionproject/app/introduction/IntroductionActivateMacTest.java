package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.app.api.client.SessionId;
import org.junit.Test;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_AUTH;

public class IntroductionActivateMacTest extends BrambleTestCase {

	@Test
	public void anHonestActivateVerifiesAtThePeer() throws Exception {
		java.lang.reflect.Constructor<?> cc = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl")
				.getDeclaredConstructor(Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
						Class.forName(
								"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		CryptoComponent cryptoComponent = (CryptoComponent) cc.newInstance(
				new org.zerionproject.core.test.TestSecureRandomProvider(),
				null);
		IntroductionCryptoImpl crypto = new IntroductionCryptoImpl(
				cryptoComponent, null, null);
		Author introducer = getAuthor();
		Author a = getAuthor();
		Author b = getAuthor();
		GroupId g = new GroupId(getRandomId());
		SessionId sid = new SessionId(getRandomId());
		SecretKey finalMaster = getSecretKey();

		IntroduceeSession alice = authed(crypto, IntroduceeSession.getInitial(
				g, sid, introducer, true, b), getSecretKey());
		IntroduceeSession bob = authed(crypto, IntroduceeSession.getInitial(
				g, sid, introducer, false, a), getSecretKey());
		alice = IntroduceeProtocolEngine.withFinalKeys(crypto, alice,
				finalMaster);
		bob = IntroduceeProtocolEngine.withFinalKeys(crypto, bob, finalMaster);

		crypto.verifyActivateMac(crypto.activateMac(alice), bob);
		crypto.verifyActivateMac(crypto.activateMac(bob), alice);

		IntroduceeSession other = IntroduceeProtocolEngine.withFinalKeys(
				crypto, authed(crypto, IntroduceeSession.getInitial(g, sid,
						introducer, false, a), getSecretKey()),
				getSecretKey());
		try {
			crypto.verifyActivateMac(crypto.activateMac(alice), other);
			org.junit.Assert.fail();
		} catch (java.security.GeneralSecurityException expected) {
		}
	}

	private static IntroduceeSession authed(IntroductionCryptoImpl crypto,
			IntroduceeSession s, SecretKey preMaster) {
		Message m = new Message(new MessageId(getRandomId()),
				new GroupId(getRandomId()), 1L, new byte[1]);
		return IntroduceeSession.addLocalAuth(s, AWAIT_AUTH, m, preMaster,
				crypto.deriveMacKey(preMaster, true),
				crypto.deriveMacKey(preMaster, false));
	}
}
