package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ConnectionKeyMaterialLifetimeTest {

	@Test(timeout = 60_000)
	public void anEndedConnectionWipesItsSessionKeys() throws Exception {
		CryptoComponent crypto = (CryptoComponent) construct(
				"org.zerionproject.core.crypto.CryptoComponentImpl",
				new TestSecureRandomProvider(), null);
		Class<?> ratchetClass = Class.forName(
				"org.zerionproject.core.crypto.pcs.PcsRatchetImpl");
		Constructor<?> rc = ratchetClass.getDeclaredConstructors()[0];
		rc.setAccessible(true);
		Object[] args = new Object[rc.getParameterCount()];
		args[0] = crypto;
		PcsRatchet ratchet = (PcsRatchet) rc.newInstance(args);
		MlKemProvider mlKem = (MlKemProvider) construct(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl",
				crypto.getSecureRandom());
		Mode3FullRatchet m3f = (Mode3FullRatchet) construct(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl",
				crypto, mlKem);
		ZwfSessionFactory factory = new ZwfSessionFactory(crypto, m3f);
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);

		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);
		ZwfSession aliceSession = factory.deriveSession(
				new SecretKey(rootBytes.clone()), true);
		ZwfSession bobSession = factory.deriveSession(
				new SecretKey(rootBytes.clone()), false);
		ZwfDuplexConnection alice = new ZwfDuplexConnection(2, aliceSession,
				new ZwfStreamCounter(new Mem()), crypto, ratchet, m3f,
				XSalsa20Poly1305AuthenticatedCipher::new, aIn, aOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(1, bobSession,
				new ZwfStreamCounter(new Mem()), crypto, ratchet, m3f,
				XSalsa20Poly1305AuthenticatedCipher::new, bIn, bOut);
		for (int i = 0; i < 3; i++) {
			alice.sendMessage(ZmmRecord.cover());
			bob.sendMessage(ZmmRecord.cover());
			aOut.flush();
			bOut.flush();
			assertNotNull(bob.receiveMessage());
			assertNotNull(alice.receiveMessage());
		}
		alice.destroyKeyMaterial();
		bob.destroyKeyMaterial();
		for (ZwfSession s : new ZwfSession[] {aliceSession, bobSession}) {
			assertZero(s.getSendTagKey());
			assertZero(s.getSendHeaderKey());
			assertZero(s.getRecvTagKey());
			assertZero(s.getRecvHeaderKey());
			assertZero(s.getSendState().getRootKey());
			assertZero(s.getRecvState().getRootKey());
		}
		for (ZwfDuplexConnection c : new ZwfDuplexConnection[] {alice, bob}) {
			byte[] dk = c.currentMode3FullState().getOurActiveKeyPair()
					.getDecapsulationKey();
			boolean zero = true;
			for (byte b : dk) zero &= b == 0;
			assertTrue(zero);
		}
	}

	private static void assertZero(SecretKey k) {
		assertNotNull(k);
		boolean zero = true;
		for (byte b : k.getBytes()) zero &= b == 0;
		assertTrue("key material survived the connection", zero);
	}

	private static Object construct(String className, Object... args)
			throws Exception {
		Class<?> c = Class.forName(className);
		for (Constructor<?> ctor : c.getDeclaredConstructors()) {
			if (ctor.getParameterCount() != args.length) continue;
			ctor.setAccessible(true);
			try {
				return ctor.newInstance(args);
			} catch (IllegalArgumentException ignored) {
			}
		}
		throw new AssertionError("no constructor for " + className);
	}

	private static final class Mem implements StreamCounterStore {
		private final Map<Long, Long> m = new HashMap<>();

		@Override
		public synchronized long loadHighWater(int c, int d) {
			Long v = m.get((((long) c) << 1) | (d & 1L));
			return v == null ? 0 : v;
		}

		@Override
		public synchronized void storeHighWater(int c, int d, long hw) {
			m.put((((long) c) << 1) | (d & 1L), hw);
		}
	}
}
