package org.zerionproject.sync;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmFragmenter;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.zerionproject.transport.ZwfSession;
import org.zerionproject.transport.ZwfSessionFactory;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;

public class ZppTwoSessionReassemblyTest {

	private static final int BOB_SEES_ALICE_AS = 7;
	private static final long WAIT_MS = 25_000;

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private ZwfSessionFactory sessionFactory;
	private ZmmSyncCodec codec;
	private GroupId group;

	private final List<Link> links = new ArrayList<>();
	private final List<Thread> threads = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		crypto = (CryptoComponent) construct(
				"org.zerionproject.core.crypto.CryptoComponentImpl",
				new TestSecureRandomProvider(), null);
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				Thread.sleep(ms);
			}
		};
		ratchet = new PcsRatchetImpl(crypto);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKem = (MlKemProvider) providerCtor.newInstance(
				crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKem);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
		MessageFactory messageFactory = (MessageFactory) construct(
				"org.zerionproject.core.sync.MessageFactoryImpl", crypto);
		RecordReaderFactory readers = (RecordReaderFactory) construct(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		RecordWriterFactory writers = (RecordWriterFactory) construct(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
		SyncRecordReaderFactory syncReaders =
				(SyncRecordReaderFactory) construct(
						"org.zerionproject.core.sync.SyncRecordReaderFactoryImpl",
						messageFactory, readers);
		SyncRecordWriterFactory syncWriters =
				(SyncRecordWriterFactory) construct(
						"org.zerionproject.core.sync.SyncRecordWriterFactoryImpl",
						messageFactory, writers);
		codec = new ZmmSyncCodec(syncWriters, syncReaders);
		group = getGroup(getClientId(), 1).getId();
	}

	@After
	public void tearDown() throws Exception {
		for (Link l : links) l.close();
		for (Thread t : threads) t.join(10_000);
	}

	private static final class RecordingDb {
		final Map<String, Integer> received = new ConcurrentHashMap<>();
		final DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					String name = method.getName();
					if (name.equals("transaction")) {
						((DbRunnable<?>) args[1]).run(
								new Transaction(null, false));
						return null;
					}
					if (name.equals("receiveMessage")) {
						Message m = (Message) args[2];
						received.merge(toHexString(m.getBody()), 1,
								Integer::sum);
						return null;
					}
					Class<?> r = method.getReturnType();
					if (r == boolean.class) return false;
					if (r == int.class) return 0;
					if (r == long.class) return 0L;
					return null;
				});

		Map<String, Integer> snapshot() {
			return new TreeMap<>(received);
		}
	}

	private static final class CapturingRegistry
			implements ZppConnectionRegistry {
		final Map<Integer, ZppSendScheduler> schedulers =
				new ConcurrentHashMap<>();

		@Override
		public void onConnectionOpened(int contactId,
				ZppSendScheduler scheduler, int maxRecordBytes) {
			schedulers.put(contactId, scheduler);
		}

		@Override
		public void onConnectionClosed(int contactId,
				ZppSendScheduler scheduler) {
			schedulers.remove(contactId, scheduler);
		}
	}

	private static final class MemStore implements StreamCounterStore {
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

	private final class Link {
		final int aliceSideId;
		final PipedOutputStream aOut = new PipedOutputStream();
		final PipedInputStream bIn;
		final PipedOutputStream bOut = new PipedOutputStream();
		final PipedInputStream aIn;
		final ZwfDuplexConnection alice;
		final ZwfDuplexConnection bob;
		final int maxRecordBytes;
		long nextMessageId = 0;

		Link(int aliceSideId) throws Exception {
			this.aliceSideId = aliceSideId;
			bIn = new PipedInputStream(aOut, 1 << 20);
			aIn = new PipedInputStream(bOut, 1 << 20);
			byte[] rootBytes = new byte[SecretKey.LENGTH];
			crypto.getSecureRandom().nextBytes(rootBytes);
			SecretKey root = new SecretKey(rootBytes);
			ZwfSession aliceSession = sessionFactory.deriveSession(root, true);
			ZwfSession bobSession = sessionFactory.deriveSession(root, false);
			Supplier<AuthenticatedCipher> ciphers =
					XSalsa20Poly1305AuthenticatedCipher::new;
			alice = new ZwfDuplexConnection(aliceSideId, aliceSession,
					new ZwfStreamCounter(new MemStore()), crypto, ratchet,
					mode3FullRatchet, ciphers, aIn, aOut);
			bob = new ZwfDuplexConnection(BOB_SEES_ALICE_AS, bobSession,
					new ZwfStreamCounter(new MemStore()), crypto, ratchet,
					mode3FullRatchet, ciphers, bIn, bOut);
			maxRecordBytes = alice.getMaxMessageLength();
		}

		void close() throws IOException {
			aOut.close();
			bOut.close();
			aIn.close();
			bIn.close();
		}

		List<byte[]> fragmentsOf(Message m) throws IOException {
			return ZmmFragmenter.fragment(ZmmConstants.TYPE_SYNC,
					codec.encodeMessage(m), nextMessageId++, maxRecordBytes);
		}
	}

	private final class Endpoints {
		final RecordingDb bobDb = new RecordingDb();
		final ZmmDbRecordSink bobSink = new ZmmDbRecordSink(bobDb.db, codec);
		final CapturingRegistry aliceRegistry = new CapturingRegistry();
		final ZppConnectionRunnerImpl aliceRunner = new ZppConnectionRunnerImpl(
				new ZmmDbRecordSink(new RecordingDb().db, codec),
				aliceRegistry, 5);
		final ZppConnectionRunnerImpl bobRunner = new ZppConnectionRunnerImpl(
				bobSink, new CapturingRegistry(), 5);

		Link open(int aliceSideId) throws Exception {
			Link l = new Link(aliceSideId);
			links.add(l);
			start("alice-" + aliceSideId, () -> aliceRunner.run(aliceSideId,
					l.alice));
			start("bob-" + aliceSideId, () -> bobRunner.run(BOB_SEES_ALICE_AS,
					l.bob));
			return l;
		}

		ZppSendScheduler scheduler(Link l) throws InterruptedException {
			long deadline = System.currentTimeMillis() + WAIT_MS;
			while (System.currentTimeMillis() < deadline) {
				ZppSendScheduler s = aliceRegistry.schedulers.get(l.aliceSideId);
				if (s != null) return s;
				Thread.sleep(2);
			}
			throw new AssertionError("no scheduler for " + l.aliceSideId);
		}
	}

	private interface Body {
		void run() throws Exception;
	}

	private void start(String name, Body body) {
		Thread t = new Thread(() -> {
			try {
				body.run();
			} catch (Exception ignored) {
			}
		}, name);
		threads.add(t);
		t.start();
	}

	private Message record(int bodyLength) {
		return getMessage(group, bodyLength);
	}

	private static void send(ZppSendScheduler s, List<byte[]> frames) {
		for (byte[] f : frames) s.enqueueRecord(f, true);
	}

	private Message sendThenSync(Link l, ZppSendScheduler s,
			List<byte[]> frames, RecordingDb db) throws Exception {
		send(s, frames);
		Message sentinel = record(64);
		send(s, l.fragmentsOf(sentinel));
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (!db.snapshot().containsKey(toHexString(sentinel.getBody()))) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("sentinel on " + l.aliceSideId
						+ " never reached Bob");
			}
			Thread.sleep(5);
		}
		return sentinel;
	}

	private static Map<String, Integer> expect(Message... ms) {
		Map<String, Integer> out = new TreeMap<>();
		for (Message m : ms) out.put(toHexString(m.getBody()), 1);
		return out;
	}

	private static void awaitDelivered(RecordingDb db,
			Map<String, Integer> expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (System.currentTimeMillis() < deadline
				&& !db.snapshot().equals(expected)) {
			Thread.sleep(10);
		}
		Thread.sleep(200);
		Map<String, Integer> got = db.snapshot();
		assertEquals("records reaching Bob's database (body -> times): "
				+ describe(got, expected), expected, got);
	}

	private static String describe(Map<String, Integer> got,
			Map<String, Integer> expected) {
		int missing = 0, extra = 0, dup = 0;
		for (String k : expected.keySet()) if (!got.containsKey(k)) missing++;
		for (Map.Entry<String, Integer> e : got.entrySet()) {
			if (!expected.containsKey(e.getKey())) extra++;
			else if (e.getValue() > 1) dup++;
		}
		return "expected " + expected.size() + " records, delivered "
				+ got.size() + ", missing " + missing + ", unknown or corrupted "
				+ extra + ", delivered more than once " + dup;
	}

	@Test(timeout = 90_000)
	public void recordsOnTwoLiveSessionsWithTheSameMessageIdsAllArriveIntact()
			throws Exception {
		Endpoints e = new Endpoints();
		Link a = e.open(1);
		Link b = e.open(2);
		ZppSendScheduler sa = e.scheduler(a);
		ZppSendScheduler sb = e.scheduler(b);
		Message a0 = record(5000), a1 = record(9000);
		Message b0 = record(7000), b1 = record(9000);
		List<byte[]> fa0 = a.fragmentsOf(a0), fa1 = a.fragmentsOf(a1);
		List<byte[]> fb0 = b.fragmentsOf(b0), fb1 = b.fragmentsOf(b1);
		List<byte[]> fb1Reversed = new ArrayList<>(fb1);
		Collections.reverse(fb1Reversed);
		Message s1 = sendThenSync(a, sa, fa0.subList(0, fa0.size() / 2),
				e.bobDb);
		Message s2 = sendThenSync(b, sb, fb0.subList(0, fb0.size() / 2),
				e.bobDb);
		send(sa, fa0.subList(fa0.size() / 2, fa0.size()));
		send(sb, fb0.subList(fb0.size() / 2, fb0.size()));
		Message s3 = sendThenSync(a, sa, fa1.subList(0, fa1.size() / 2),
				e.bobDb);
		Message s4 = sendThenSync(b, sb, fb1Reversed.subList(0,
				fb1Reversed.size() / 2), e.bobDb);
		send(sa, fa1.subList(fa1.size() / 2, fa1.size()));
		send(sb, fb1Reversed.subList(fb1Reversed.size() / 2,
				fb1Reversed.size()));
		awaitDelivered(e.bobDb, expect(a0, a1, b0, b1, s1, s2, s3, s4));
	}

	@Test(timeout = 90_000)
	public void closingSessionAMidRecordLetsSessionBCompleteAndTheRetransmissionArrives()
			throws Exception {
		Endpoints e = new Endpoints();
		Link a = e.open(1);
		Link b = e.open(2);
		ZppSendScheduler sa = e.scheduler(a);
		ZppSendScheduler sb = e.scheduler(b);
		Message ra = record(8000), rb = record(8000);
		List<byte[]> fa = a.fragmentsOf(ra), fb = b.fragmentsOf(rb);
		Message s1 = sendThenSync(a, sa, fa.subList(0, fa.size() / 2),
				e.bobDb);
		Message s2 = sendThenSync(b, sb, fb.subList(0, fb.size() / 2),
				e.bobDb);
		a.close();
		Thread.sleep(500);
		send(sb, fb.subList(fb.size() / 2, fb.size()));
		send(sb, b.fragmentsOf(ra));
		awaitDelivered(e.bobDb, expect(ra, rb, s1, s2));
	}

	@Test(timeout = 90_000)
	public void closingSessionBMidRecordLetsSessionACompleteAndTheRetransmissionArrives()
			throws Exception {
		Endpoints e = new Endpoints();
		Link a = e.open(1);
		Link b = e.open(2);
		ZppSendScheduler sa = e.scheduler(a);
		ZppSendScheduler sb = e.scheduler(b);
		Message ra = record(6000), rb = record(6000);
		List<byte[]> fa = a.fragmentsOf(ra), fb = b.fragmentsOf(rb);
		Message s1 = sendThenSync(a, sa, fa.subList(0, fa.size() / 2),
				e.bobDb);
		Message s2 = sendThenSync(b, sb, fb.subList(0, fb.size() / 2),
				e.bobDb);
		b.close();
		Thread.sleep(500);
		send(sa, fa.subList(fa.size() / 2, fa.size()));
		send(sa, a.fragmentsOf(rb));
		awaitDelivered(e.bobDb, expect(ra, rb, s1, s2));
	}

	@Test(timeout = 90_000)
	public void duplicateFragmentsAndARetransmittedRecordAreStoredOnce()
			throws Exception {
		Endpoints e = new Endpoints();
		Link a = e.open(1);
		Link b = e.open(2);
		ZppSendScheduler sa = e.scheduler(a);
		ZppSendScheduler sb = e.scheduler(b);
		Message r = record(7000);
		List<byte[]> fa = a.fragmentsOf(r);
		List<byte[]> withDuplicates = new ArrayList<>();
		for (byte[] f : fa) {
			withDuplicates.add(f);
			withDuplicates.add(f);
		}
		send(sa, withDuplicates);
		awaitDelivered(e.bobDb, expect(r));
		send(sb, b.fragmentsOf(r));
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (System.currentTimeMillis() < deadline
				&& e.bobDb.snapshot().get(toHexString(r.getBody())) < 2) {
			Thread.sleep(10);
		}
		assertEquals("a retransmission is handed to the database again, "
						+ "which keeps one copy; a duplicated fragment is not",
				Integer.valueOf(2), e.bobDb.snapshot().get(
						toHexString(r.getBody())));
		assertEquals(1, e.bobDb.snapshot().size());
	}

	@Test(timeout = 90_000)
	public void aRestartOfBobsSideDropsPartialRecordsAndTheRetransmissionArrivesOnce()
			throws Exception {
		Endpoints before = new Endpoints();
		Link a = before.open(1);
		ZppSendScheduler sa = before.scheduler(a);
		Message r = record(9000);
		List<byte[]> fa = a.fragmentsOf(r);
		Message s1 = sendThenSync(a, sa, fa.subList(0, fa.size() / 2),
				before.bobDb);
		a.close();
		Endpoints after = new Endpoints();
		Link a2 = after.open(1);
		Link b2 = after.open(2);
		ZppSendScheduler sa2 = after.scheduler(a2);
		after.scheduler(b2);
		send(sa2, a2.fragmentsOf(r));
		awaitDelivered(after.bobDb, expect(r));
		assertEquals("only the sentinel reached the database before the "
				+ "restart", expect(s1), before.bobDb.snapshot());
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
}
