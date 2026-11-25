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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_LEGACY_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS;
import static org.zerionproject.sync.ZppConnectionRunnerImpl.SPARSE_REFRESH_RECV_FRAMES;

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
			holds(() -> conn.sent.size() == 1, 700,
					"awake with a frozen clock, nothing is due");
			now.addAndGet(40_000);
			await(() -> conn.sent.size() == 2, "the keepalive after waking");
			holds(() -> conn.sent.size() == 2, 1_000,
					"exactly one keepalive for the whole sleep");
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

	@Test(timeout = 30_000)
	public void aTolerantPeerFrameHalfAPeriodAfterOurLastFrameIsAnsweredAtOnce()
			throws Exception {
		long opened = now.get();
		assertFalse("half a period after the opening frame is not a slot",
				ZppConnectionRunnerImpl.sparseFrameDue(opened, 0,
						opened + SPARSE_KEEPALIVE_MS / 2, SPARSE_KEEPALIVE_MS));
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				registry, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(tolerantPeerParams());
			conn.deliver(ZmmRecord.cover());
			await(() -> events.contains("recv-cover"), "the first cover frame");
			holds(() -> conn.sent.size() == 1, 700,
					"a frame right after our own is not answered");
			assertFalse(events.contains("KEEPALIVE"));
			now.addAndGet(SPARSE_KEEPALIVE_LEGACY_MS / 2);
			conn.deliver(ZmmRecord.cover());
			holds(() -> conn.sent.size() == 1, 700,
					"half the legacy period is not half the sparse period");
			now.addAndGet(SPARSE_KEEPALIVE_MS / 2 - SPARSE_KEEPALIVE_LEGACY_MS / 2);
			conn.deliver(ZmmRecord.cover());
			await(() -> conn.sent.size() == 2, "the echoed keepalive");
			assertTrue(ZmmRecord.isCover(conn.sent.get(1)));
			await(() -> events.contains("KEEPALIVE"),
					"the echo takes the wake lock briefly");
			assertTrue("the lock is taken before the echo leaves",
					events.indexOf("KEEPALIVE")
							< events.lastIndexOf("sent-cover"));
			holds(() -> conn.sent.size() == 2, 700,
					"one answer per received frame at most");
			conn.deliver(ZmmRecord.cover());
			holds(() -> conn.sent.size() == 2, 700,
					"the next frame within half a period is not answered");
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
		assertFalse("an echo is not connection data",
				afterEvent("recv-cover").contains("CONNECTION_DATA"));
	}

	@Test
	public void theSenderJudgesAnEchoAgainstItsOwnLastFrame() {
		long period = SPARSE_KEEPALIVE_MS;
		long opened = 1_700_000_000_000L;
		long later = opened + period / 2;
		assertFalse("not a slot, so only the echo could make it due",
				ZppConnectionRunnerImpl.sparseFrameDue(opened, 0, later,
						period));
		assertTrue("the reader's echo stands when nothing was sent since",
				ZppConnectionRunnerImpl.sparseSendDue(opened, 0, later,
						period, true));
		assertFalse("a frame that just went out cancels the echo",
				ZppConnectionRunnerImpl.sparseSendDue(later, 0, later,
						period, true));
		assertFalse("no echo, no slot: nothing",
				ZppConnectionRunnerImpl.sparseSendDue(opened, 0, later,
						period, false));
		assertTrue("a slot is due with or without an echo",
				ZppConnectionRunnerImpl.sparseSendDue(opened, 0,
						opened + 2 * period, period, false));
	}

	@Test(timeout = 30_000)
	public void aLegacyPeerIsNotEchoed() throws Exception {
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				registry, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			now.addAndGet(SPARSE_KEEPALIVE_LEGACY_MS / 2);
			conn.deliver(ZmmRecord.cover());
			await(() -> events.contains("recv-cover"), "the cover frame");
			holds(() -> conn.sent.size() == 1, 700,
					"a peer that never announced the sparse cadence");
			assertFalse(events.contains("KEEPALIVE"));
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	@Test(timeout = 30_000)
	public void aRefreshThatIsDueAndAnEchoInTheSameFrameSendOneFrame()
			throws Exception {
		long opened = now.get();
		assertFalse("half a period after the opening frame is not a slot",
				ZppConnectionRunnerImpl.sparseFrameDue(opened,
						SPARSE_REFRESH_RECV_FRAMES - 1,
						opened + SPARSE_KEEPALIVE_MS / 2, SPARSE_KEEPALIVE_MS));
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				registry, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(tolerantPeerParams());
			for (int i = 1; i < SPARSE_REFRESH_RECV_FRAMES - 1; i++) {
				conn.deliver(ZmmRecord.cover());
			}
			await(() -> count("recv-cover") == SPARSE_REFRESH_RECV_FRAMES - 2,
					"the frames before the refresh");
			holds(() -> conn.sent.size() == 1, 300, "nothing due yet");
			now.addAndGet(SPARSE_KEEPALIVE_MS / 2);
			conn.deliver(ZmmRecord.cover());
			await(() -> conn.sent.size() == 2,
					"the frame for the refresh and the echo");
			holds(() -> conn.sent.size() == 2, 1_000,
					"the refresh and the echo together send one frame");
			conn.deliver(ZmmRecord.encode(ZmmConstants.TYPE_TEXT,
					"hi".getBytes(StandardCharsets.UTF_8)));
			await(() -> events.contains("delivered"), "the text record");
			holds(() -> conn.sent.size() == 2, 700,
					"a record right after our own frame is not answered");
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
		assertEquals("exactly one echo was noted",
				1, count("KEEPALIVE"));
	}

	@Test(timeout = 30_000)
	public void aRecordQueuedInSparseModeGoesOutAtOnceWithoutACoverFrame()
			throws Exception {
		ScriptedConnection conn = connection();
		AtomicReference<ZppSendScheduler> captured = new AtomicReference<>();
		ZppConnectionRegistry capturing = new ZppConnectionRegistry() {
			@Override
			public void onConnectionOpened(int contactId,
					ZppSendScheduler scheduler, int maxRecordBytes) {
				captured.set(scheduler);
			}

			@Override
			public void onConnectionClosed(int contactId,
					ZppSendScheduler scheduler) {
			}
		};
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				capturing, new ZppPacingPolicy(), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(tolerantPeerParams());
			await(() -> captured.get() != null, "the scheduler");
			captured.get().enqueueControl(ZmmRecord.encode(
					ZmmConstants.TYPE_TEXT,
					"queued".getBytes(StandardCharsets.UTF_8)));
			await(() -> conn.sent.size() == 2, "the queued record");
			assertFalse("the queued record went out, not a cover frame",
					ZmmRecord.isCover(conn.sent.get(1)));
			holds(() -> conn.sent.size() == 2, 700,
					"no cover frame follows the record");
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	@Test(timeout = 30_000)
	public void aDenseConnectionNeverEchoes() throws Exception {
		ScriptedConnection conn = connection();
		ZppConnectionRunnerImpl runner =
				new ZppConnectionRunnerImpl(records, registry, densePacing(),
						sink);
		Thread t = run(runner, conn);
		try {
			await(() -> count("sent-cover") >= 1, "cover frames");
			conn.deliver(ZmmRecord.encode(ZmmConstants.TYPE_LINK_PARAMS,
					ZppLinkParams.encode(true)));
			await(() -> events.contains("recv-real"), "the peer parameters");
			conn.deliver(ZmmRecord.cover());
			await(() -> events.contains("recv-cover"), "a received frame");
			holds(() -> !events.contains("KEEPALIVE"), 500,
					"a dense connection to a tolerant peer does not echo");
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	private static byte[] tolerantPeerParams() {
		return ZmmRecord.encode(ZmmConstants.TYPE_LINK_PARAMS,
				ZppLinkParams.encode(false));
	}

	private static ZppPacing densePacing() {
		return new ZppPacing() {
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
	}

	@Test
	public void echoIsWantedHalfAPeriodAfterOurLastFrameOrOnAClockJump() {
		long period = SPARSE_KEEPALIVE_MS;
		assertFalse(ZppConnectionRunnerImpl.echoWanted(
				ZppConnectionRunnerImpl.NEVER, 1_000, period));
		assertFalse(ZppConnectionRunnerImpl.echoWanted(
				1_000, 1_000 + period / 2 - 1, period));
		assertTrue(ZppConnectionRunnerImpl.echoWanted(
				1_000, 1_000 + period / 2, period));
		assertTrue(ZppConnectionRunnerImpl.echoWanted(1_000, 999, period));
	}

	@Test
	public void aClockThatJumpedBackOrFarAheadMakesAKeepaliveDue() {
		long sentAt = 1_700_000_000_000L;
		assertFalse(ZppConnectionRunnerImpl.sparseFrameDue(sentAt, 0,
				sentAt + 1_000, ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
		assertTrue("backwards jump", ZppConnectionRunnerImpl.sparseFrameDue(
				sentAt, 0, sentAt - 1,
				ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
		assertTrue("an hour ahead is past the slot",
				ZppConnectionRunnerImpl.sparseFrameDue(sentAt, 0,
						sentAt + 60 * 60_000L,
						ZppConnectionRunnerImpl.SPARSE_KEEPALIVE_MS));
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
			holds(() -> !afterEvent("recv-cover").contains("CONNECTION_DATA"),
					300, "a received cover frame does not pulse");
			conn.deliver(ZmmRecord.encode(ZmmConstants.TYPE_TEXT,
					"hi".getBytes(StandardCharsets.UTF_8)));
			await(() -> events.contains("delivered"), "the text record");
			await(() -> afterEvent("recv-real").contains("CONNECTION_DATA"),
					"the pulse for a received record");
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

	@Test(timeout = 60_000)
	public void onADenseLinkOnlyARecordThatOpensTheWindowMakesItActive()
			throws Exception {
		ScriptedConnection conn = connection();
		AtomicReference<ZppSendScheduler> opened = new AtomicReference<>();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				capturing(opened), stepPacing(true), now::get, sink);
		assertTrue(runner.grantsOpeningWindow(1, now.get()));
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(ZmmRecord.encode(ZmmConstants.TYPE_LINK_PARAMS,
					ZppLinkParams.encode(true)));
			await(() -> opened.get() != null, "the outgoing source");
			ZppSendScheduler scheduler = opened.get();

			scheduler.enqueueRecord(text(1), true, false, false, null);
			now.addAndGet(STEP_IDLE_MAX_MS);
			await(() -> count("sent-real") == 2, "the body at an idle slot");
			int frames = conn.sent.size();
			now.addAndGet(STEP_BETWEEN_MS);
			holds(() -> conn.sent.size() == frames, STEP_SETTLE_MS,
					"a body that opens no window leaves the link idle");

			scheduler.enqueueRecord(text(2), false, true, false, null);
			await(() -> count("sent-real") == 3, "the offer after the wake");
			int after = conn.sent.size();
			now.addAndGet(STEP_BETWEEN_MS);
			await(() -> conn.sent.size() > after,
					"an active slot after the offer");
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	@Test(timeout = 60_000)
	public void onASparseLinkTheMarkedBodyStillCountsAsThisSidesSend()
			throws Exception {
		ScriptedConnection conn = connection();
		AtomicReference<ZppSendScheduler> opened = new AtomicReference<>();
		ZppConnectionRunnerImpl runner = new ZppConnectionRunnerImpl(records,
				capturing(opened), stepPacing(false), now::get, sink);
		Thread t = run(runner, conn);
		try {
			await(() -> conn.sent.size() == 1, "the link parameters frame");
			conn.deliver(tolerantPeerParams());
			await(() -> opened.get() != null, "the outgoing source");
			ZppSendScheduler scheduler = opened.get();
			assertTrue(runner.gateFor(1).admitReceipt());
			assertFalse(runner.gateFor(1).admitReceipt());

			scheduler.enqueueRecord(text(1), false, true, false, null);
			await(() -> count("sent-real") == 2, "the offer");
			holds(() -> scheduler.getQueueDepth() == 0, 100, "sent");
			assertFalse("an offer is no send of this side on a sparse link",
					runner.gateFor(1).admitReceipt());

			scheduler.enqueueRecord(text(2), true, false, false, null);
			await(() -> count("sent-real") == 3, "the body");
			await(() -> runner.gateFor(1).admitReceipt(),
					"the reply window the body opens");
			assertTrue(runner.gateFor(1).admitReceipt());
		} finally {
			conn.end();
			t.join(AWAIT_MS);
		}
		assertFalse(t.isAlive());
	}

	private static final long STEP_ACTIVE_MS = 30;
	private static final long STEP_IDLE_MS = 600;
	private static final long STEP_IDLE_MAX_MS = 800;
	private static final long STEP_BETWEEN_MS = 100;
	private static final long STEP_SETTLE_MS = 1_500;

	private static ZppPacing stepPacing(boolean cover) {
		return new ZppPacing() {
			@Override
			public long activeIntervalMs() {
				return STEP_ACTIVE_MS;
			}

			@Override
			public long idleIntervalMs() {
				return STEP_IDLE_MS;
			}

			@Override
			public long idleAfterMs() {
				return 60_000L;
			}

			@Override
			public boolean coverTraffic() {
				return cover;
			}
		};
	}

	private static ZppConnectionRegistry capturing(
			AtomicReference<ZppSendScheduler> opened) {
		return new ZppConnectionRegistry() {
			@Override
			public void onConnectionOpened(int contactId,
					ZppSendScheduler scheduler, int maxRecordBytes) {
				opened.set(scheduler);
			}

			@Override
			public void onConnectionClosed(int contactId,
					ZppSendScheduler scheduler) {
			}
		};
	}

	private static byte[] text(int value) {
		return ZmmRecord.encode(ZmmConstants.TYPE_TEXT,
				new byte[] {(byte) value});
	}

	private List<String> afterEvent(String event) {
		synchronized (events) {
			int i = events.indexOf(event);
			if (i < 0) return Collections.emptyList();
			return new ArrayList<>(events.subList(i + 1, events.size()));
		}
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

	private static void holds(BooleanSupplier condition, long ms,
			String what) throws InterruptedException {
		long deadline = System.currentTimeMillis() + ms;
		while (System.currentTimeMillis() < deadline) {
			assertTrue(what, condition.getAsBoolean());
			Thread.sleep(20);
		}
		assertTrue(what, condition.getAsBoolean());
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
