package org.zerionproject.core.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.junit.Test;

import java.util.Map;

import static org.zerionproject.core.api.plugin.TransportId.MAX_TRANSPORT_ID_LENGTH;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.zerionproject.core.util.StringUtils.toUtf8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A transport properties map arrives from a remote peer inside an
 * introduction ACCEPT and inside the contact exchange payload. Every key
 * must be rejected with a FormatException before a TransportId is built
 * from it: TransportId's constructor throws an unchecked exception for an
 * empty or over-long identifier, and an unchecked exception escaping a
 * validator killed the process and left the message to be re-validated at
 * every start.
 */
public class TransportPropertiesMapValidationTest extends BrambleMockTestCase {

	private final ClientHelperImpl clientHelper = new ClientHelperImpl(
			context.mock(DatabaseComponent.class),
			context.mock(MessageFactory.class),
			context.mock(BdfReaderFactory.class),
			context.mock(BdfWriterFactory.class),
			context.mock(MetadataParser.class),
			context.mock(MetadataEncoder.class),
			context.mock(CryptoComponent.class),
			context.mock(AuthorFactory.class));

	private static BdfDictionary mapWithKey(String key) {
		return BdfDictionary.of(new BdfEntry(key, BdfDictionary.of(
				new BdfEntry("k", "v"))));
	}

	private void assertRejected(String key) {
		try {
			clientHelper.parseAndValidateTransportPropertiesMap(
					mapWithKey(key));
			fail("Key of " + toUtf8(key).length + " bytes was accepted");
		} catch (FormatException expected) {
		}
	}

	@Test
	public void testRejectsEmptyTransportId() {
		assertRejected("");
	}

	@Test
	public void testRejectsTransportIdOneByteOverMaximum() {
		assertRejected(getRandomString(MAX_TRANSPORT_ID_LENGTH + 1));
	}

	@Test
	public void testRejectsTransportIdFarOverMaximum() {
		assertRejected(getRandomString(MAX_TRANSPORT_ID_LENGTH * 10));
	}

	@Test
	public void testLengthIsMeasuredInUtf8BytesNotCharacters() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 34; i++) sb.append('€');
		String key = sb.toString();
		assertTrue(key.length() < MAX_TRANSPORT_ID_LENGTH);
		assertTrue(toUtf8(key).length > MAX_TRANSPORT_ID_LENGTH);
		assertRejected(key);
	}

	@Test
	public void testAcceptsTransportIdAtMaximumLength() throws Exception {
		String key = getRandomString(MAX_TRANSPORT_ID_LENGTH);
		Map<TransportId, TransportProperties> parsed =
				clientHelper.parseAndValidateTransportPropertiesMap(
						mapWithKey(key));
		assertEquals(1, parsed.size());
		assertEquals("v", parsed.get(new TransportId(key)).get("k"));
	}

	@Test
	public void testAcceptsShortestTransportId() throws Exception {
		Map<TransportId, TransportProperties> parsed =
				clientHelper.parseAndValidateTransportPropertiesMap(
						mapWithKey("a"));
		assertEquals(1, parsed.size());
	}

	@Test
	public void testNoUncheckedExceptionForAnyKeyLength() {
		for (int len = 0; len <= MAX_TRANSPORT_ID_LENGTH + 5; len++) {
			String key = getRandomString(len);
			try {
				clientHelper.parseAndValidateTransportPropertiesMap(
						mapWithKey(key));
				assertTrue(len >= 1 && len <= MAX_TRANSPORT_ID_LENGTH);
			} catch (FormatException e) {
				assertTrue(len < 1 || len > MAX_TRANSPORT_ID_LENGTH);
			}
		}
	}
}
