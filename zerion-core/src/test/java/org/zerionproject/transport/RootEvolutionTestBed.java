package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.test.PermissiveOnionClientAuth;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.message.ZmmRecord;
import org.zerionproject.sync.ZppConnectionRegistry;
import org.zerionproject.sync.ZppConnectionRunnerImpl;
import org.zerionproject.sync.ZppRecordSink;
import org.zerionproject.sync.ZppSendScheduler;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

final class RootEvolutionTestBed {

	final CryptoComponent crypto;
	final PcsRatchet ratchet;
	final Mode3FullRatchet mode3FullRatchet;
	final MlKemProvider mlKem;
	final ZwfSessionFactory sessionFactory;
	final AtomicLong clock = new AtomicLong(1_000_000_000L);

	RootEvolutionTestBed() throws Exception {
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
		Class<?> ratchetClass = Class.forName(
				"org.zerionproject.core.crypto.pcs.PcsRatchetImpl");
		Constructor<?> rc = ratchetClass.getDeclaredConstructors()[0];
		rc.setAccessible(true);
		Object[] ratchetArgs = new Object[rc.getParameterCount()];
		ratchetArgs[0] = crypto;
		ratchet = (PcsRatchet) rc.newInstance(ratchetArgs);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		mlKem = (MlKemProvider) providerCtor.newInstance(
				crypto.getSecureRandom());
		Class<?> m3fImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> m3fCtor = m3fImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		m3fCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) m3fCtor.newInstance(crypto,
				mlKem);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	SecretKey randomKey() {
		byte[] b = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(b);
		return new SecretKey(b);
	}

	void passTime() {
		clock.addAndGet(RootEvolutionManager.MIN_EVOLUTION_INTERVAL_MS + 1);
	}

	static final class MemRootKeyStore extends RootKeyStore {

		private final Map<Integer, long[]> epochs = new HashMap<>();
		private final Map<Integer, byte[]> current = new HashMap<>();
		private final Map<Integer, byte[]> pending = new HashMap<>();
		private final Map<Integer, Boolean> confirmed = new HashMap<>();
		volatile boolean paused = false;
		final AtomicInteger pendingStores = new AtomicInteger();

		@SuppressWarnings("ConstantConditions")
		MemRootKeyStore() {
			super(null);
		}

		synchronized void put(ContactId c, ContactRootKeys keys) {
			epochs.put(c.getInt(), new long[] {keys.getEpoch()});
			current.put(c.getInt(), keys.getCurrent().getBytes().clone());
			SecretKey p = keys.getPending();
			if (p == null) pending.remove(c.getInt());
			else pending.put(c.getInt(), p.getBytes().clone());
			confirmed.put(c.getInt(), keys.isPendingConfirmed());
		}

		@Override
		@Nullable
		public synchronized ContactRootKeys load(ContactId c) {
			long[] e = epochs.get(c.getInt());
			if (e == null) return null;
			byte[] p = pending.get(c.getInt());
			return new ContactRootKeys(e[0],
					new SecretKey(current.get(c.getInt()).clone()),
					p == null ? null : new SecretKey(p.clone()),
					Boolean.TRUE.equals(confirmed.get(c.getInt())));
		}

		@Override
		public boolean storePending(ContactId c, long fromEpoch,
				SecretKey key, boolean conf) {
			synchronized (this) {
				long[] e = epochs.get(c.getInt());
				if (e == null || e[0] != fromEpoch) return false;
				pending.put(c.getInt(), key.getBytes().clone());
				confirmed.put(c.getInt(), conf);
				pendingStores.incrementAndGet();
			}
			notifyChanged(c);
			return true;
		}

		@Override
		public boolean discardPending(ContactId c, long fromEpoch) {
			synchronized (this) {
				long[] e = epochs.get(c.getInt());
				if (e == null || e[0] != fromEpoch) return false;
				if (pending.remove(c.getInt()) == null) return false;
				confirmed.put(c.getInt(), false);
			}
			notifyChanged(c);
			return true;
		}

		@Override
		public boolean promote(ContactId c, long fromEpoch) {
			synchronized (this) {
				long[] e = epochs.get(c.getInt());
				if (e == null || e[0] != fromEpoch) return false;
				byte[] p = pending.remove(c.getInt());
				if (p == null) return false;
				e[0] = fromEpoch + 1;
				current.put(c.getInt(), p);
				confirmed.put(c.getInt(), false);
			}
			notifyChanged(c);
			return true;
		}

		@Override
		public boolean isPaused(long now) {
			return paused;
		}

		private final java.util.Set<Integer> outOfSync =
				java.util.concurrent.ConcurrentHashMap.newKeySet();

		@Override
		public void markOutOfSync(ContactId c, boolean flag) {
			if (flag) outOfSync.add(c.getInt());
			else outOfSync.remove(c.getInt());
		}

		@Override
		public boolean isOutOfSync(ContactId c) {
			return outOfSync.contains(c.getInt());
		}
	}

	static final class Cut {
		final String device;
		final byte kind;
		volatile boolean fired = false;

		Cut(String device, byte kind) {
			this.device = device;
			this.kind = kind;
		}
	}

	static final class Hold {
		final byte kind;
		volatile boolean caught = false;
		@Nullable
		private volatile Runnable deliver;

		Hold(byte kind) {
			this.kind = kind;
		}

		void release() {
			Runnable d = deliver;
			deliver = null;
			if (d != null) d.run();
		}
	}

	static final class Gate {
		final byte kind;
		final java.util.concurrent.CountDownLatch reached =
				new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch open =
				new java.util.concurrent.CountDownLatch(1);

		Gate(byte kind) {
			this.kind = kind;
		}

		boolean awaitReached(long ms) throws InterruptedException {
			return reached.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
		}
	}

	final class Device {

		final String name;
		final int peerId;
		final boolean alice;
		final MemRootKeyStore store = new MemRootKeyStore();
		final ZwfStreamCounter counter;
		final ZwfTagRecogniser recogniser;
		final List<String> received =
				Collections.synchronizedList(new ArrayList<>());
		final AtomicInteger connections = new AtomicInteger();
		@Nullable
		volatile Cut cut;
		@Nullable
		volatile Runnable cutAction;
		@Nullable
		volatile Hold hold;
		@Nullable
		volatile Gate gate;
		final List<Byte> receivedKinds =
				Collections.synchronizedList(new ArrayList<>());
		final ZtpConnectionHandlerImpl handler;
		final Provider provider;
		@Nullable
		final RootEvolutionManager evolutions;

		Device(String name, int peerId, boolean alice, ContactRootKeys keys,
				boolean evolves) {
			this(name, peerId, alice, keys, evolves, null);
		}

		Device(String name, int peerId, boolean alice, ContactRootKeys keys,
				boolean evolves, @Nullable RootEvolutionManager manager) {
			this(name, peerId, alice, keys, evolves, manager, null);
		}

		Device(String name, int peerId, boolean alice, ContactRootKeys keys,
				boolean evolves, @Nullable RootEvolutionManager manager,
				@Nullable RootEvolutionManager.PeerIdentity identity) {
			this.name = name;
			this.peerId = peerId;
			this.alice = alice;
			store.put(new ContactId(peerId), keys);
			counter = new ZwfStreamCounter(new MemCounterStore());
			recogniser = new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
			provider = new Provider();
			store.addListener(provider);
			provider.register();
			ZtpConnectionEstablisher establisher = new ZtpConnectionEstablisher(
					crypto, ratchet, mode3FullRatchet, sessionFactory, counter,
					cipherFactory());
			evolutions = manager != null ? manager
					: evolves ? new CuttingManager(this, identity) : null;
			handler = new ZtpConnectionHandlerImpl(establisher, provider,
					new ZppConnectionRunnerImpl(new Sink(), new Registry(), 5),
					noOpConnectionRegistry(), new PermissiveOnionClientAuth(),
					evolutions);
		}

		ContactRootKeys keys() {
			ContactRootKeys k = store.load(new ContactId(peerId));
			if (k == null) throw new AssertionError();
			return k;
		}

		void resetKeys(ContactRootKeys keys) {
			store.put(new ContactId(peerId), keys);
			provider.register();
		}

		final class Provider implements ZtpSessionProvider,
				RootKeyStore.Listener {

			void register() {
				ContactRootKeys keys = store.load(new ContactId(peerId));
				if (keys == null) {
					recogniser.remove(peerId);
					return;
				}
				Map<Long, SecretKey> tagKeys = new LinkedHashMap<>();
				for (long e : new long[] {keys.getEpoch(),
						keys.getPendingEpoch()}) {
					SecretKey root = keys.getKey(e);
					if (root != null) {
						tagKeys.put(e,
								sessionFactory.deriveRecvTagKey(root, e, alice));
					}
				}
				recogniser.register(peerId, tagKeys,
						counter.currentRecvHighWater(peerId));
			}

			@Override
			public void rootKeysChanged(ContactId c) {
				register();
			}

			@Override
			public int recogniseIncoming(byte[] tag) {
				ZwfTagRecogniser.Match m = recogniser.recognise(tag);
				return m == null ? -1 : m.contactId;
			}

			@Override
			@Nullable
			public StoredContactSession getStoredSession(int contactId) {
				ContactRootKeys keys = store.load(new ContactId(contactId));
				if (keys == null) return null;
				return new StoredContactSession(keys, alice,
						counter.generation(contactId));
			}

			@Override
			public void sessionClosed(int contactId) {
				recogniser.advanceTo(contactId,
						counter.currentRecvHighWater(contactId));
			}
		}

		final class Registry implements ZppConnectionRegistry {
			@Override
			public void onConnectionOpened(int contactId,
					ZppSendScheduler scheduler, int maxRecordBytes) {
				int n = connections.incrementAndGet();
				scheduler.enqueueRecord(ZmmRecord.encode(
						ZmmConstants.TYPE_SYNC, (name + "-" + n)
								.getBytes(StandardCharsets.UTF_8)), true);
			}

			@Override
			public void onConnectionClosed(int contactId,
					ZppSendScheduler scheduler) {
			}
		}

		final class Sink implements ZppRecordSink {
			@Override
			public void deliver(int contactId, long sessionId, int type,
					byte[] payload) {
				if (type == ZmmConstants.TYPE_SYNC) {
					received.add(new String(payload, StandardCharsets.UTF_8));
				}
			}

			@Override
			public void onDisconnected(int contactId, long sessionId) {
			}
		}
	}

	final class CuttingManager extends RootEvolutionManager {

		private final Device device;

		CuttingManager(Device device) {
			this(device, null);
		}

		CuttingManager(Device device,
				@Nullable RootEvolutionManager.PeerIdentity identity) {
			super(device.store, crypto, mlKem, clock::get,
					identity != null ? identity : noIdentity());
			this.device = device;
		}

		@Override
		public ZwfControlHandler newEvolution(ContactId c, boolean alice) {
			ZwfControlHandler inner = super.newEvolution(c, alice);
			return new ZwfControlHandler() {
				@Override
				public void start(Sender sender) {
					inner.start(new Sender() {
						@Override
						public void send(byte[] payload) {
							Cut cut = device.cut;
							if (cut != null && !cut.fired
									&& payload[1] == cut.kind) {
								cut.fired = true;
								Runnable a = device.cutAction;
								if (a != null) a.run();
								return;
							}
							Hold hold = device.hold;
							if (hold != null && !hold.caught
									&& payload[1] == hold.kind) {
								hold.caught = true;
								hold.deliver = () -> sender.send(payload);
								return;
							}
							sender.send(payload);
						}

						@Override
						public void sendWhenDue(Supplier<byte[]> builder) {
							sender.sendWhenDue(() -> {
								byte[] payload = builder.get();
								if (payload == null) return null;
								Cut cut = device.cut;
								if (cut != null && !cut.fired
										&& payload[1] == cut.kind) {
									cut.fired = true;
									Runnable a = device.cutAction;
									if (a != null) a.run();
									return null;
								}
								return payload;
							});
						}
					});
				}

				@Override
				public void onRecord(byte[] payload) {
					if (payload.length > 1) {
						device.receivedKinds.add(payload[1]);
						Gate g = device.gate;
						if (g != null && payload[1] == g.kind) {
							g.reached.countDown();
							try {
								g.open.await();
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
						}
					}
					inner.onRecord(payload);
				}

				@Override
				public void onPeerStreamAuthenticated(long epoch) {
					inner.onPeerStreamAuthenticated(epoch);
				}

				@Override
				public void close() {
					inner.close();
				}
			};
		}
	}

	static final class ReplayingManager extends RootEvolutionManager {

		volatile long epoch = 0;

		ReplayingManager(RootEvolutionTestBed bed) {
			super(new MemRootKeyStore(), bed.crypto, bed.mlKem, bed.clock::get);
		}

		@Override
		public ZwfControlHandler newEvolution(ContactId c, boolean alice) {
			return new ZwfControlHandler() {
				@Override
				public void start(Sender sender) {
					sender.send(RootEvolutionRecord.hello(epoch,
							new byte[RootEvolutionRecord.MAC_LENGTH], (byte) 0));
				}

				@Override
				public void onRecord(byte[] payload) {
				}

				@Override
				public void onPeerStreamAuthenticated(long epoch) {
				}

				@Override
				public void close() {
				}
			};
		}
	}

	static RootEvolutionManager.PeerIdentity noIdentity() {
		return new RootEvolutionManager.PeerIdentity() {
			@Override
			public boolean knowsPeerKey(ContactId c) {
				return true;
			}

			@Override
			@Nullable
			public byte[][] ownKeyAndSignature() {
				return null;
			}

			@Override
			public void learnPeerKey(ContactId c, byte[] mlDsaKey,
					byte[] signature) {
			}
		};
	}

	static final class Outcome {
		@Nullable
		volatile Throwable dialerError;
		@Nullable
		volatile Throwable acceptorError;
		volatile boolean acceptorSawData;
	}

	final class Live {
		final Device dialer;
		final Device acceptor;
		final Outcome outcome = new Outcome();
		private final Runnable close;
		private final Thread d;
		private final Thread a;

		Live(Device dialer, Device acceptor) throws IOException {
			this.dialer = dialer;
			this.acceptor = acceptor;
			PipedOutputStream dialerOut = new PipedOutputStream();
			PipedInputStream acceptorIn =
					new PipedInputStream(dialerOut, 1 << 20);
			PipedOutputStream acceptorOut = new PipedOutputStream();
			PipedInputStream dialerIn =
					new PipedInputStream(acceptorOut, 1 << 20);
			close = () -> {
				closeQuietly(dialerOut);
				closeQuietly(acceptorOut);
				closeQuietly(dialerIn);
				closeQuietly(acceptorIn);
			};
			d = new Thread(() -> {
				try {
					dialer.handler.handleOutgoing(TorConstants.ID,
							dialer.peerId, dialerIn, flushing(dialerOut));
				} catch (Throwable t) {
					outcome.dialerError = t;
				}
			});
			a = new Thread(() -> {
				try {
					acceptor.handler.handleIncoming(TorConstants.ID,
							acceptorIn, flushing(acceptorOut));
				} catch (Throwable t) {
					outcome.acceptorError = t;
				}
			});
			d.start();
			a.start();
		}

		boolean isOpen() {
			return d.isAlive() && a.isAlive();
		}

		boolean await(BooleanSupplier done, long ms)
				throws InterruptedException {
			long deadline = System.currentTimeMillis() + ms;
			while (System.currentTimeMillis() < deadline && isOpen()) {
				if (done.getAsBoolean()) return true;
				Thread.sleep(10);
			}
			return done.getAsBoolean();
		}

		void close() throws InterruptedException {
			close.run();
			d.join(10_000);
			a.join(10_000);
			if (d.isAlive() || a.isAlive()) throw new AssertionError("hung");
		}
	}

	Outcome connect(Device dialer, Device acceptor, BooleanSupplier done)
			throws Exception {
		PipedOutputStream dialerOut = new PipedOutputStream();
		PipedInputStream acceptorIn = new PipedInputStream(dialerOut, 1 << 20);
		PipedOutputStream acceptorOut = new PipedOutputStream();
		PipedInputStream dialerIn = new PipedInputStream(acceptorOut, 1 << 20);
		Runnable close = () -> {
			closeQuietly(dialerOut);
			closeQuietly(acceptorOut);
			closeQuietly(dialerIn);
			closeQuietly(acceptorIn);
		};
		dialer.cutAction = close;
		acceptor.cutAction = close;
		Outcome o = new Outcome();
		Thread d = new Thread(() -> {
			try {
				dialer.handler.handleOutgoing(TorConstants.ID, dialer.peerId,
						dialerIn, flushing(dialerOut));
			} catch (Throwable t) {
				o.dialerError = t;
			}
		});
		Thread a = new Thread(() -> {
			try {
				acceptor.handler.handleIncoming(TorConstants.ID, acceptorIn,
						flushing(acceptorOut));
			} catch (Throwable t) {
				o.acceptorError = t;
			}
		});
		d.start();
		a.start();
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline && d.isAlive()
				&& a.isAlive() && !done.getAsBoolean()) {
			Thread.sleep(10);
		}
		Thread.sleep(50);
		close.run();
		d.join(10_000);
		a.join(10_000);
		if (d.isAlive() || a.isAlive()) throw new AssertionError("hung");
		return o;
	}

	void parallel(Device dialer, Device acceptor) throws Exception {
		int dBefore = dialer.received.size();
		int aBefore = acceptor.received.size();
		long epochBefore = Math.max(dialer.keys().getEpoch(),
				acceptor.keys().getEpoch());
		AtomicLong markersAt = new AtomicLong(0);
		BooleanSupplier done = () -> {
			boolean markers = dialer.received.size() >= dBefore + 2
					&& acceptor.received.size() >= aBefore + 2;
			if (!markers) return false;
			long now = System.currentTimeMillis();
			markersAt.compareAndSet(0, now);
			if (settled(dialer, acceptor, epochBefore)) return true;
			return now - markersAt.get() > 1_500;
		};
		Throwable[] errors = new Throwable[2];
		Thread first = new Thread(() -> {
			try {
				connect(dialer, acceptor, done);
			} catch (Throwable t) {
				errors[0] = t;
			}
		});
		Thread second = new Thread(() -> {
			try {
				connect(dialer, acceptor, done);
			} catch (Throwable t) {
				errors[1] = t;
			}
		});
		first.start();
		second.start();
		first.join(30_000);
		second.join(30_000);
		if (errors[0] != null) throw new AssertionError(errors[0]);
		if (errors[1] != null) throw new AssertionError(errors[1]);
	}

	void glare(Device x, Device y) throws Exception {
		int xBefore = x.received.size();
		int yBefore = y.received.size();
		long epochBefore = Math.max(x.keys().getEpoch(), y.keys().getEpoch());
		BooleanSupplier done = () -> x.received.size() >= xBefore + 2
				&& y.received.size() >= yBefore + 2
				&& settled(x, y, epochBefore);
		Throwable[] errors = new Throwable[2];
		Thread first = new Thread(() -> {
			try {
				connect(x, y, done);
			} catch (Throwable t) {
				errors[0] = t;
			}
		});
		Thread second = new Thread(() -> {
			try {
				connect(y, x, done);
			} catch (Throwable t) {
				errors[1] = t;
			}
		});
		first.start();
		second.start();
		first.join(30_000);
		second.join(30_000);
		if (errors[0] != null) throw new AssertionError(errors[0]);
		if (errors[1] != null) throw new AssertionError(errors[1]);
	}

	boolean exchange(Device dialer, Device acceptor) throws Exception {
		int dBefore = dialer.received.size();
		int aBefore = acceptor.received.size();
		long epochBefore = Math.max(dialer.keys().getEpoch(),
				acceptor.keys().getEpoch());
		AtomicLong markersAt = new AtomicLong(0);
		connect(dialer, acceptor, () -> {
			boolean markers = dialer.received.size() > dBefore
					&& acceptor.received.size() > aBefore;
			if (!markers) return false;
			long now = System.currentTimeMillis();
			markersAt.compareAndSet(0, now);
			if (settled(dialer, acceptor, epochBefore)) return true;
			return now - markersAt.get() > 1_500;
		});
		return dialer.received.size() > dBefore
				&& acceptor.received.size() > aBefore;
	}

	static boolean settled(Device x, Device y, long afterEpoch) {
		ContactRootKeys kx = x.keys();
		ContactRootKeys ky = y.keys();
		return kx.getPending() == null && ky.getPending() == null
				&& kx.getEpoch() == ky.getEpoch()
				&& kx.getEpoch() > afterEpoch;
	}

	static boolean sameRoot(Device x, Device y) {
		ContactRootKeys kx = x.keys();
		ContactRootKeys ky = y.keys();
		return kx.getEpoch() == ky.getEpoch() && kx.getPending() == null
				&& ky.getPending() == null && java.util.Arrays.equals(
				kx.getCurrent().getBytes(), ky.getCurrent().getBytes());
	}

	static boolean isFormatException(@Nullable Throwable t) {
		return t instanceof FormatException;
	}

	private static Supplier<AuthenticatedCipher> cipherFactory() {
		return XSalsa20Poly1305AuthenticatedCipher::new;
	}

	private static java.io.OutputStream flushing(java.io.OutputStream out) {
		return new java.io.FilterOutputStream(out) {
			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
				out.flush();
			}
		};
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}

	static final class MemCounterStore implements StreamCounterStore {
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

	static ConnectionRegistry noOpConnectionRegistry() {
		return new ConnectionRegistry() {
			@Override
			public void registerIncomingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn) {
			}

			@Override
			public void registerOutgoingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
			}

			@Override
			public void unregisterConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, boolean incoming,
					boolean exception) {
			}

			@Override
			public void setPriority(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
			}

			@Override
			public Collection<ContactId> getConnectedContacts(TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public Collection<ContactId> getConnectedOrBetterContacts(
					TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public boolean isConnected(ContactId c, TransportId t) {
				return false;
			}

			@Override
			public boolean isConnected(ContactId c) {
				return false;
			}

			@Override
			public boolean registerConnection(PendingContactId p) {
				return true;
			}

			@Override
			public void unregisterConnection(PendingContactId p,
					boolean success) {
			}
		};
	}
}
