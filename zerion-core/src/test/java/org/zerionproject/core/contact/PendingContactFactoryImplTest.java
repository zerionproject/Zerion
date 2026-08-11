package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.util.Base32;
import org.jmock.Expectations;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Locale;

import static java.lang.System.arraycopy;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.BASE32_LINK_BYTES_CLASSICAL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.ID_LABEL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.RAW_LINK_BYTES_CLASSICAL;
import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class PendingContactFactoryImplTest extends BrambleMockTestCase {

	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private final Clock clock = context.mock(Clock.class);
	private final KeyParser keyParser = context.mock(KeyParser.class);

	private final PendingContactFactory pendingContactFactory =
			new PendingContactFactoryImpl(crypto, clock);
	private final String alias = getRandomString(MAX_AUTHOR_NAME_LENGTH);
	private final PublicKey publicKey = getAgreementPublicKey();
	private final byte[] idBytes = getRandomId();
	private final long timestamp = System.currentTimeMillis();

	@Test(expected = FormatException.class)
	public void testRejectsSyntacticallyInvalidLink() throws Exception {
		pendingContactFactory.createPendingContact("briar://potato", alias);
	}

	@Test
	public void testRejectsLinkWithUnknownFormatVersion() throws Exception {
		String link = "zerion://" + encodeLink(FORMAT_VERSION + 1);
		try {
			pendingContactFactory.createPendingContact(link, alias);
			fail();
		} catch (UnsupportedVersionException e) {
			assertFalse(e.isTooOld());
		}
	}

	@Test(expected = FormatException.class)
	public void testRejectsLinkWithInvalidPublicKey() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));
			oneOf(keyParser).parsePublicKey(publicKey.getEncoded());
			will(throwException(new GeneralSecurityException()));
		}});

		pendingContactFactory.createPendingContact(
				"zerion://" + encodeLink(), alias);
	}

	@Test(expected = FormatException.class)
	public void testRejectsLinkWithoutPrefix() throws Exception {
		pendingContactFactory.createPendingContact(encodeLink(), alias);
	}

	@Test(expected = FormatException.class)
	public void testRejectsLinkWithBriarPrefix() throws Exception {
		pendingContactFactory.createPendingContact(
				"briar://" + encodeLink(), alias);
	}

	@Test
	public void testAcceptsValidLinkWithZerionPrefix() throws Exception {
		testAcceptsValidLink("zerion://" + encodeLink());
	}

	@Test
	public void testAcceptsValidLinkWithZerionPrefixAndQueryParams() throws Exception {
		testAcceptsValidLink("zerion://" + encodeLink() + "?foo=bar&baz=qux");
	}

	@Test(expected = FormatException.class)
	public void testRejectsLinkWithRubbish() throws Exception {
		pendingContactFactory.createPendingContact(
				"before " + encodeLink() + " after", alias);
	}

	@Test(expected = FormatException.class)
	public void testRejectsLinkWithZerionPrefixAndRubbish() throws Exception {
		pendingContactFactory.createPendingContact(
				"before zerion://" + encodeLink() + " after", alias);
	}

	private void testAcceptsValidLink(String link) throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));
			oneOf(keyParser).parsePublicKey(publicKey.getEncoded());
			will(returnValue(publicKey));
			oneOf(crypto).hash(ID_LABEL, publicKey.getEncoded());
			will(returnValue(idBytes));
			oneOf(clock).currentTimeMillis();
			will(returnValue(timestamp));
		}});

		PendingContact p =
				pendingContactFactory.createPendingContact(link, alias);
		assertArrayEquals(idBytes, p.getId().getBytes());
		assertArrayEquals(publicKey.getEncoded(),
				p.getPublicKey().getEncoded());
		assertEquals(alias, p.getAlias());
		assertEquals(timestamp, p.getTimestamp());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreateHandshakeLinkRejectsInvalidKeyType() {
		PublicKey invalidPublicKey = context.mock(PublicKey.class);

		context.checking(new Expectations() {{
			oneOf(invalidPublicKey).getKeyType();
			will(returnValue(KEY_TYPE_SIGNATURE));
		}});

		pendingContactFactory.createHandshakeLink(invalidPublicKey);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCreateHandshakeLinkRejectsInvalidKeyLength() {
		PublicKey invalidPublicKey = context.mock(PublicKey.class);
		byte[] invalidPublicKeyBytes =
				getRandomBytes(MAX_AGREEMENT_PUBLIC_KEY_BYTES + 1);

		context.checking(new Expectations() {{
			oneOf(invalidPublicKey).getKeyType();
			will(returnValue(KEY_TYPE_AGREEMENT));
			oneOf(invalidPublicKey).getEncoded();
			will(returnValue(invalidPublicKeyBytes));
		}});

		pendingContactFactory.createHandshakeLink(invalidPublicKey);
	}

	@Test
	public void testCreateAndParseLink() throws Exception {
		context.checking(new Expectations() {{
			oneOf(crypto).getAgreementKeyParser();
			will(returnValue(keyParser));
			oneOf(keyParser).parsePublicKey(publicKey.getEncoded());
			will(returnValue(publicKey));
			oneOf(crypto).hash(ID_LABEL, publicKey.getEncoded());
			will(returnValue(idBytes));
			oneOf(clock).currentTimeMillis();
			will(returnValue(timestamp));
		}});

		String link = pendingContactFactory.createHandshakeLink(publicKey);
		PendingContact p =
				pendingContactFactory.createPendingContact(link, alias);
		assertArrayEquals(idBytes, p.getId().getBytes());
		assertArrayEquals(publicKey.getEncoded(),
				p.getPublicKey().getEncoded());
		assertEquals(alias, p.getAlias());
		assertEquals(timestamp, p.getTimestamp());
	}

	private String encodeLink() {
		return encodeLink(FORMAT_VERSION_CLASSICAL);
	}

	private String encodeLink(int formatVersion) {
		byte[] rawLink = new byte[RAW_LINK_BYTES_CLASSICAL];
		rawLink[0] = (byte) formatVersion;
		byte[] publicKeyBytes = publicKey.getEncoded();
		arraycopy(publicKeyBytes, 0, rawLink, 1, publicKeyBytes.length);
		String base32 = Base32.encode(rawLink).toLowerCase(Locale.US);
		assertEquals(BASE32_LINK_BYTES_CLASSICAL, base32.length());
		return base32;
	}
}
