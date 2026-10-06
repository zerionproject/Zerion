package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AsyncSealedSenderSecretClearingTest {

	@Test
	public void encapsulatedSecretIsWipedWhenKeyDerivationFails()
			throws Exception {
		CryptoComponent real = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		AtomicReference<HybridEncapsulationResult> captured =
				new AtomicReference<>();
		CryptoComponent crypto = (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals(
							"deriveHybridSharedSecretAsResponder")) {
						throw new GeneralSecurityException();
					}
					Object result;
					try {
						result = method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (result instanceof HybridEncapsulationResult) {
						captured.set((HybridEncapsulationResult) result);
					}
					return result;
				});
		AsyncSealedSender sealer = new AsyncSealedSender(crypto);
		KeyPair rIdSig = real.generateHybridSignatureKeyPair();
		KeyPair rIdAgree = real.generateHybridAgreementKeyPair();
		KeyPair rPrekey = real.generateHybridAgreementKeyPair();
		KeyPair sIdSig = real.generateHybridSignatureKeyPair();
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		r.recipientAgreementPub = rPrekey.getPublic();
		r.prekeyKind = AsyncEnvelope.PREKEY_KIND_ONE_TIME;
		r.prekeyId = new byte[AsyncEnvelope.PREKEY_ID_BYTES];
		r.signedPrekeyId = 7L;
		r.recipientIdentitySigPub = rIdSig.getPublic().getEncoded();
		r.recipientIdentityAgreePub = rIdAgree.getPublic().getEncoded();
		r.senderIdentitySigPub = sIdSig.getPublic().getEncoded();
		r.senderIdentitySigPrivateKey = sIdSig.getPrivate();
		r.messageType = 5;
		r.payload = new byte[] {1, 2, 3};
		r.ttl = 3600L;
		r.dedupId = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		r.sendTimestamp = 1L;
		try {
			sealer.seal(r);
			fail();
		} catch (GeneralSecurityException expected) {
		}
		HybridEncapsulationResult enc = captured.get();
		assertNotNull(enc);
		byte[] secret = enc.getSharedSecret();
		boolean allZero = true;
		for (byte b : secret) allZero &= b == 0;
		assertTrue(allZero);
	}
}
