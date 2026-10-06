package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfTag;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

public class InboundTagSearchCostTest {

	private CryptoComponent real;
	private final AtomicLong macs = new AtomicLong();
	private final AtomicLong now = new AtomicLong(1_000_000L);

	@Before
	public void setUp() throws Exception {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = impl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		real = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
	}

	private CryptoComponent counting() {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("mac")) macs.incrementAndGet();
					try {
						return method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private SecretKey key(int i) {
		byte[] b = new byte[SecretKey.LENGTH];
		b[0] = (byte) i;
		b[1] = 7;
		return new SecretKey(b);
	}

	private ZtpSessionProviderImpl provider(ZwfTagRecogniser recogniser) {
		ZtpSessionProviderImpl p = new ZtpSessionProviderImpl(recogniser, null,
				null, null, null, null, null, Runnable::run, null);
		p.clock = now::get;
		return p;
	}

	private long costOfAStrangersTag(int contacts) {
		ZwfTagRecogniser recogniser =
				new ZwfTagRecogniser(counting(), REPLAY_WINDOW_SIZE);
		for (int i = 1; i <= contacts; i++) recogniser.register(i, key(i), 0);
		ZtpSessionProviderImpl provider = provider(recogniser);
		byte[] junk = new byte[16];
		junk[3] = 1;
		macs.set(0);
		assertEquals(-1, provider.recogniseIncoming(junk));
		return macs.get();
	}

	@Test
	public void aStrangersTagCostsTheSameForOneContactOrMany() {
		long one = costOfAStrangersTag(1);
		long many = costOfAStrangersTag(6);
		assertEquals(one, many);
	}

	@Test
	public void aContactPastTheWindowIsFoundWithinOneRound() {
		int contacts = 6;
		ZwfTagRecogniser recogniser =
				new ZwfTagRecogniser(real, REPLAY_WINDOW_SIZE);
		for (int i = 1; i <= contacts; i++) recogniser.register(i, key(i), 0);
		ZtpSessionProviderImpl provider = provider(recogniser);
		byte[] far = ZwfTag.computeTag(real, key(4), REPLAY_WINDOW_SIZE + 900);
		int found = -1;
		for (int attempt = 0; attempt < contacts && found < 0; attempt++) {
			found = provider.recogniseIncoming(far);
			now.addAndGet(ZtpSessionProviderImpl.INBOUND_SEARCH_INTERVAL_MS);
		}
		assertEquals(4, found);
	}
}
