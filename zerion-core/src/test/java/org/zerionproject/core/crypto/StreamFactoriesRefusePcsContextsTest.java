package org.zerionproject.core.crypto;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;

import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The classical stream factories build the streams that carry pairing
 * handshakes and contact exchanges. A context that carries ratchet state has
 * no consumer any more: every contact connection runs on the ZWF path, so such
 * a context is refused before any key is derived instead of being carried by
 * a chain seeded from an unsalted, period-resetting stream number.
 */
public class StreamFactoriesRefusePcsContextsTest extends BrambleMockTestCase {

	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final TransportCrypto transportCrypto =
			context.mock(TransportCrypto.class);
	private final ContactId contactId = getContactId();
	private final TransportId transportId = getTransportId();
	private final SecretKey tagKey = getSecretKey();
	private final SecretKey headerKey = getSecretKey();

	private final StreamEncrypterFactoryImpl encrypterFactory =
			new StreamEncrypterFactoryImpl(crypto, transportCrypto,
					XSalsa20Poly1305AuthenticatedCipher::new);
	private final StreamDecrypterFactoryImpl decrypterFactory =
			new StreamDecrypterFactoryImpl(
					XSalsa20Poly1305AuthenticatedCipher::new);

	@Test
	public void encrypterRefusesAContextThatCarriesRatchetState() {
		try {
			encrypterFactory.createStreamEncrypter(new ByteArrayOutputStream(),
					ratchetContext());
			fail();
		} catch (IllegalStateException expected) {
		}
	}

	@Test
	public void decrypterRefusesAContextThatCarriesRatchetState() {
		try {
			decrypterFactory.createStreamDecrypter(
					new ByteArrayInputStream(new byte[0]), ratchetContext());
			fail();
		} catch (IllegalStateException expected) {
		}
	}

	@Test
	public void classicalContextsStillBuildTheHandshakeStreams() {
		context.checking(new Expectations() {{
			allowing(crypto).getSecureRandom();
			will(returnValue(new SecureRandom()));
			allowing(crypto).generateSecretKey();
			will(returnValue(getSecretKey()));
			oneOf(transportCrypto).encodeTag(with(any(byte[].class)),
					with(tagKey), with(any(int.class)), with(any(long.class)));
		}});

		StreamEncrypter e = encrypterFactory.createStreamEncrypter(
				new ByteArrayOutputStream(), classicalContext());
		StreamDecrypter d = decrypterFactory.createStreamDecrypter(
				new ByteArrayInputStream(new byte[0]), classicalContext());
		assertTrue(e instanceof StreamEncrypterImpl);
		assertTrue(d instanceof StreamDecrypterImpl);
	}

	private StreamContext classicalContext() {
		return new StreamContext(contactId, null, transportId, tagKey,
				headerKey, 3L, false, false, false, null, null);
	}

	private StreamContext ratchetContext() {
		PcsSessionState state = new PcsSessionState(getSecretKey(), 0, 0,
				getSecretKey(), null, false, 0, null);
		return new StreamContext(contactId, null, transportId, tagKey,
				headerKey, 3L, false, false, true, state, null);
	}
}
