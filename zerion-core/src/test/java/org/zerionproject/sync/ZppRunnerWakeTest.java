package org.zerionproject.sync;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.tor.TorActivitySink;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.zerionproject.transport.ZwfSession;
import org.zerionproject.transport.ZwfSessionFactory;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The sparse sender's keepalive must survive a sleeping device, and only
 * frames that carry something may wake the Tor wake policy.
 */
public class ZppRunnerWakeTest {

	private static final long AWAIT_MS = 10_000;

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private ZwfSessionFactory sessionFactory;

	private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
	private final List<String> events =
			Collections.synchronizedList(new ArrayList<>());
	private final TorActivitySink sink = a -> events.add(a.name());
	private final ZppConnectionRegistry registry =
			new ZppConnectionRegistry() {
				@Override
				public void onConnectionOpened(int contactId,
						ZppSendScheduler scheduler, int maxRecordBytes) {
				}

				@Override
				public void onConnectionClosed(int contactId,
						ZppSendScheduler scheduler) {
				}
			};
	private final ZppRecordSink records = new ZppRecordSink() {
		@Override
		public void deliver(int contactId, long sessionId, int type,
				byte[] payload) {
			events.add("delivered");
		}

		@Override
		public void onDisconnected(int contactId, long sessionId) {
		}
	};

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		ratchet = new PcsRatchetImpl(crypto);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKemProvider = (MlKemProvider) providerCtor
				.newInstance(crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKemProvider);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	@Test(timeout = 30_000)
	public void aSleepOf40sSendsExactlyOneKeepaliveOnWaking()
			throws Exception {
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				registry, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			Thread.sleep(700);
			assertEquals("awake with a frozen clock, nothing is due", 1,
					conn.sent.size());
			now.addAndGet(40_000);
			await(() -> conn.sent.size() == 2, "the keepalive after waking");
			Thread.sleep(1_000);
			assertEquals("exactly one keepalive for the whole sleep", 2,
					conn.sent.size());
			assertTrue(ZmmRecord.isCover(conn.sent.get(1)));
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
		int cover = events.indexOf("sent-cover");
		assertTrue(cover > 0);
		assertTrue("the link parameters frame pulsed the sink",
				events.subList(0, cover).contains("CONNECTION_DATA"));
		assertFalse("the keepalive did not",
				events.subList(cover + 1, events.size())
						.contains("CONNECTION_DATA"));
	}

	@Test
	public void aClockThatJumpedBackOrFarAheadMakesAKeepaliveDue() {
		long sentAt = 1_700_000_000_000L;
		assertFalse(ZppConnectionRunnerImpl.sparseFrameDue(sentAt, 0,
				sentAt + 1_000, ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
		assertTrue("backwards jump", ZppConnectionRunnerImpl.sparseFrameDue(
				sentAt, 0, sentAt - 1,
				ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
		assertTrue("jump of more than an hour",
				ZppConnectionRunnerImpl.sparseFrameDue(sentAt, 0,
						sentAt + ZppConnectionRunnerImpl.MAX_KEEPALIVE_GAP_MS
								+ 1, ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
	}

	@Test(timeout = 30_000)
	public void realFramesPulseTheSinkAndCoverFramesDoNot() throws Exception {
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				registry, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(ZmmRecord.cover());
			await(() -> events.contains("recv-cover"), "the cover frame");
			Thread.sleep(300);
			List<String> afterCover = new ArrayList<>(events.subList(
					events.indexOf("recv-cover") + 1, events.size()));
			assertFalse("a received cover frame does not pulse " + afterCover,
					afterCover.contains("CONNECTION_DATA"));
			conn.deliver(ZmmRecord.encode(ZmmConstants.TYPE_TEXT,
					"hi".getBytes(StandardCharsets.UTF_8)));
			await(() -> events.contains("delivered"), "the text record");
			List<String> afterReal = new ArrayList<>(events.subList(
					events.indexOf("recv-real") + 1, events.size()));
			assertTrue("a received record pulses " + afterReal,
					afterReal.contains("CONNECTION_DATA"));
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	@Test(timeout = 30_000)
	public void aDenseCoverTrafficConnectionPulsesContinuously()
			throws Exception {
		ScriptedConnection conn = connection();
		ZppPacing dense = new ZppPacing() {
			@Override
			public long activeIntervalMs() {
				return 5;
			}

			@Override
			public long idleIntervalMs() {
				return 5;
			}

			@Override
			public long idleAfterMs() {
				return Long.MAX_VALUE;
			}

			@Override
			public boolean coverTraffic() {
				return true;
			}
		};
		ZppConnectionRunnerImpl runner =
				new ZppConnectionRunnerImpl(records, registry, dense, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> count("COVER_TRAFFIC") >= 3, "cover traffic pulses");
			assertTrue("cover frames are being sent",
					count("sent-cover") >= 1);
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	private int count(String event) {
		int n = 0;
		synchronized (events) {
			for (String e : events) if (e.equals(event)) n++;
		}
		return n;
	}

	private ScriptedConnection connection() {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		ZwfSession session =
				sessionFactory.deriveSession(new SecretKey(rootBytes), true);
		return new ScriptedConnection(session);
	}

	private Thread run(ZppConnectionRunnerImpl runner,
			ScriptedConnection conn) {
		Thread t = new Thread(() -> {
			try {
				runner.run(1, conn);
			} catch (IOException e) {
				events.add("error:" + e);
			}
		}, "runner-under-test");
		t.start();
		return t;
	}

	private static void await(BooleanSupplier condition, String what)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + AWAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(5);
		}
		fail("timed out waiting for " + what);
	}

	private static class MemStore implements StreamCounterStore {

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

	private final class ScriptedConnection extends ZwfDuplexConnection {

		private final byte[] end = new byte[0];
		private final BlockingQueue<byte[]> inbound =
				new LinkedBlockingQueue<>();
		final List<byte[]> sent = Collections.synchronizedList(
				new ArrayList<>());

		ScriptedConnection(ZwfSession session) {
			super(1, session, new ZwfStreamCounter(new MemStore()), crypto,
					ratchet, mode3FullRatchet,
					XSalsa20Poly1305AuthenticatedCipher::new,
					new ByteArrayInputStream(new byte[0]),
					new ByteArrayOutputStream());
		}

		@Override
		public void sendMessage(byte[] payload) {
			sent.add(payload);
			events.add(ZmmRecord.isCover(payload) ? "sent-cover" : "sent-real");
		}

		@Override
		public byte[] receiveMessage() throws IOException {
			byte[] r;
			try {
				r = inbound.take();
			} catch (InterruptedException e) {
				throw new InterruptedIOException();
			}
			if (r == end) return null;
			events.add(ZmmRecord.isCover(r) ? "recv-cover" : "recv-real");
			return r;
		}

		@Override
		public boolean isPqReady() {
			return true;
		}

		@Override
		public boolean lastFrameCarriedPqSecret() {
			return true;
		}

		@Override
		public void closeStreams() {
			inbound.offer(end);
		}

		void deliver(byte[] record) {
			inbound.add(record);
		}

		void end() {
			inbound.add(end);
		}
	}
}
