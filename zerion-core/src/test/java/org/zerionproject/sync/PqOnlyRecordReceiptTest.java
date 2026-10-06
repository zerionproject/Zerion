package org.zerionproject.sync;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.zerionproject.transport.ZwfSessionFactory;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PqOnlyRecordReceiptTest {

	@Test
	public void aRecordInTheClassicalOpeningFrameIsNotDelivered()
			throws Exception {
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
		SecretKey root = new SecretKey(rootBytes);

		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		ZwfDuplexConnection sender = new ZwfDuplexConnection(2,
				factory.deriveSession(root, true),
				new ZwfStreamCounter(new Mem()), crypto, ratchet, m3f,
				XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(new byte[0]), wire);
		sender.sendMessage(ZmmRecord.encode(ZmmConstants.TYPE_SYNC,
				new byte[] {1, 2, 3}));

		List<Integer> delivered =
				Collections.synchronizedList(new ArrayList<>());
		ZppRecordSink sink = new ZppRecordSink() {
			@Override
			public void deliver(int contactId, long sessionId, int type,
					byte[] payload) {
				delivered.add(type);
			}

			@Override
			public void onDisconnected(int contactId, long sessionId) {
			}
		};
		ZppConnectionRegistry registry = new ZppConnectionRegistry() {
			@Override
			public void onConnectionOpened(int contactId,
					ZppSendScheduler scheduler, int maxRecordBytes) {
			}

			@Override
			public void onConnectionClosed(int contactId,
					ZppSendScheduler scheduler) {
			}
		};
		ZwfDuplexConnection receiver = new ZwfDuplexConnection(1,
				factory.deriveSession(root, false),
				new ZwfStreamCounter(new Mem()), crypto, ratchet, m3f,
				XSalsa20Poly1305AuthenticatedCipher::new,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		new ZppConnectionRunnerImpl(sink, registry, 5).run(1, receiver);
		assertEquals(Collections.emptyList(), delivered);
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
