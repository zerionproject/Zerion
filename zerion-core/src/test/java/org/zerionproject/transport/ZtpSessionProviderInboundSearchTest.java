package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.crypto.ZwfTag;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;

import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.junit.Assert.assertEquals;

/**
 * A2-NET-02 / A2-REG-NET-02: an inbound tag from a contact whose send
 * counter ran past our receive window (its dials died at our accept while
 * strangers held the pre-tag slots) is still attributed to the contact by a
 * bounded search across contacts, and a stranger's random tags can trigger
 * that search only once per interval.
 */
public class ZtpSessionProviderInboundSearchTest {

	private CryptoComponent crypto;
	private ZwfTagRecogniser recogniser;
	private ZtpSessionProviderImpl provider;
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private final SecretKey tagKey = new SecretKey(new byte[32]);

	@Before
	public void setUp() throws Exception {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = impl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		recogniser = new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		recogniser.register(42, tagKey, 0);
		provider = new ZtpSessionProviderImpl(recogniser, null, null, null,
				null, null, null, Runnable::run, null);
		provider.clock = now::get;
	}

	private byte[] tag(long streamId) {
		return ZwfTag.computeTag(crypto, tagKey, streamId);
	}

	@Test
	public void aTagInsideTheWindowNeedsNoSearch() {
		assertEquals(42, provider.recogniseIncoming(tag(1)));
		assertEquals(42, provider.recogniseIncoming(tag(REPLAY_WINDOW_SIZE)));
	}

	@Test
	public void aContactPastTheWindowIsFoundBySearch() {
		long burned = REPLAY_WINDOW_SIZE + 3_000;
		assertEquals(42, provider.recogniseIncoming(tag(burned)));
	}

	@Test
	public void theSearchIsRationedAgainstStrangers() {
		byte[] junk = new byte[tag(1).length];
		assertEquals("first miss spends the search", -1,
				provider.recogniseIncoming(junk));
		junk[0] ^= 1;
		assertEquals(-1, provider.recogniseIncoming(junk));
		assertEquals("no search left for the contact in this interval", -1,
				provider.recogniseIncoming(tag(REPLAY_WINDOW_SIZE + 500)));
		now.addAndGet(ZtpSessionProviderImpl.INBOUND_SEARCH_INTERVAL_MS);
		assertEquals("the contact's retry gets the next search", 42,
				provider.recogniseIncoming(tag(REPLAY_WINDOW_SIZE + 500)));
	}

	@Test
	public void theSearchIsBounded() {
		long tooFar = REPLAY_WINDOW_SIZE
				+ ZtpSessionProviderImpl.INBOUND_SEARCH_GAP + 1;
		assertEquals(-1, provider.recogniseIncoming(tag(tooFar)));
	}
}
