package org.zerionproject.transport;

import org.zerionproject.tor.TorWrapper;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.zerionproject.tor.TorWrapper.TorState;
import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

@NotNullByDefault
public class ZtpTorTransport implements OverlayTransport {

	private static final int REMOTE_ONION_PORT = 80;
	private static final java.util.regex.Pattern ONION_V3 =
			java.util.regex.Pattern.compile("^[a-z2-7]{56}$");
	static final int SOCKET_TIMEOUT_MS = 20_000;
	private static final long ACCEPT_RETRY_DELAY_MS = 500;
	private static final int MAX_INBOUND_CONNECTIONS = 64;

	static final int TAG_READ_TIMEOUT_MS = 5_000;

	static final int MAX_PRE_TAG_CONNECTIONS = 16;
	static final int MAX_AUTHORIZED_INBOUND_CONNECTIONS =
			MAX_INBOUND_CONNECTIONS;
	static final int MAX_AUTHORIZED_PRE_TAG_CONNECTIONS = 8;
	static final long DEGRADED_GRACE_MS = 45_000;
	static final long MIN_RESTART_INTERVAL_MS = 60_000;
	static final long PROCESS_RESTART_BACKOFF_MIN_MS = 30_000;
	static final long PROCESS_RESTART_BACKOFF_MAX_MS = 10 * 60_000;

	interface Sleeper {
		void sleep(long ms) throws InterruptedException;
	}

	private static final class InboundBudget {
		final Semaphore inbound;
		final Semaphore preTag;

		InboundBudget(int maxInbound, int maxPreTag) {
			inbound = new Semaphore(maxInbound);
			preTag = new Semaphore(maxPreTag);
		}
	}

	private static final class PublishedService {
		final String target;
		final int remotePort;
		final String privateKey;

		PublishedService(String target, int remotePort, String privateKey) {
			this.target = target;
			this.remotePort = remotePort;
			this.privateKey = privateKey;
		}
	}

	private final TorWrapper tor;
	private final SocketFactory socketFactory;
	private final SocketFactory fastSocketFactory;
	private final Executor ioExecutor;
	private final ZtpConnectionHandler handler;
	@Nullable
	private final TorBridgeConfigurator bridgeConfigurator;
	private final TorPrivacyConfigurator privacyConfigurator;
	private final TorProcessWatch processWatch;
	private final OnionTargetFactory targets;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean torDead = new AtomicBoolean(false);
	private final Map<String, PublishedService> published =
			Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, OnionTargetListener> serviceListeners =
			new java.util.concurrent.ConcurrentHashMap<>();
	volatile Sleeper sleeper = Thread::sleep;
	private final InboundBudget openBudget = new InboundBudget(
			MAX_INBOUND_CONNECTIONS, MAX_PRE_TAG_CONNECTIONS);
	private final InboundBudget authorizedBudget = new InboundBudget(
			MAX_AUTHORIZED_INBOUND_CONNECTIONS,
			MAX_AUTHORIZED_PRE_TAG_CONNECTIONS);
	private final AtomicBoolean everConnected = new AtomicBoolean(false);
	private final Object restartLock = new Object();
	private final Object lifecycleLock = new Object();
	private final java.util.concurrent.atomic.AtomicInteger runGeneration =
			new java.util.concurrent.atomic.AtomicInteger();
	volatile LongSupplier clock = System::currentTimeMillis;
	private volatile long degradedSinceMs = 0;
	private volatile boolean republishOnConnect = false;
	private long lastRestartMs = 0;

	@Nullable
	private volatile OnionTargetListener openListener;
	@Nullable
	private volatile String openTarget;
	@Nullable
	private volatile String openOnion;
	@Nullable
	private volatile OnionTargetListener authorizedListener;
	@Nullable
	private volatile String authorizedTarget;
	@Nullable
	private volatile Runnable torRestartedListener;
	@Nullable
	private volatile Runnable torReconfiguredListener;
	@Nullable
	private volatile DialListener dialListener;

	public interface DialListener {
		void dialSucceeded(int contactId, String onion);
	}

	public void setDialListener(@Nullable DialListener listener) {
		this.dialListener = listener;
	}

	public ZtpTorTransport(TorWrapper tor, SocketFactory socketFactory,
			SocketFactory fastSocketFactory, Executor ioExecutor,
			ZtpConnectionHandler handler,
			TorBridgeConfigurator bridgeConfigurator,
			TorPrivacyConfigurator privacyConfigurator) {
		this(tor, socketFactory, fastSocketFactory, ioExecutor, handler,
				bridgeConfigurator, privacyConfigurator,
				new TorProcessWatch());
	}

	public ZtpTorTransport(TorWrapper tor, SocketFactory socketFactory,
			SocketFactory fastSocketFactory, Executor ioExecutor,
			ZtpConnectionHandler handler,
			TorBridgeConfigurator bridgeConfigurator,
			TorPrivacyConfigurator privacyConfigurator,
			TorProcessWatch processWatch) {
		this(tor, socketFactory, fastSocketFactory, ioExecutor, handler,
				bridgeConfigurator, privacyConfigurator, processWatch,
				new LoopbackOnionTargetFactory());
	}

	public ZtpTorTransport(TorWrapper tor, SocketFactory socketFactory,
			SocketFactory fastSocketFactory, Executor ioExecutor,
			ZtpConnectionHandler handler,
			TorBridgeConfigurator bridgeConfigurator,
			TorPrivacyConfigurator privacyConfigurator,
			TorProcessWatch processWatch, OnionTargetFactory targets) {
		this.targets = targets;
		this.tor = tor;
		this.socketFactory = socketFactory;
		this.fastSocketFactory = fastSocketFactory;
		this.ioExecutor = ioExecutor;
		this.handler = handler;
		this.bridgeConfigurator = bridgeConfigurator;
		this.privacyConfigurator = privacyConfigurator;
		this.processWatch = processWatch;
		processWatch.setListener(this::onControlConnectionLost);
		if (bridgeConfigurator != null) {
			bridgeConfigurator.setAppliedListener(this::torReconfigured);
		}
	}

	@Override
	public TransportId getTransportId() {
		return TorConstants.ID;
	}

	@Override
	public String getAddressPropertyKey() {
		return TorConstants.PROP_ONION_V3;
	}

	@Override
	public boolean isValidAddress(String peerOnion) {
		return ONION_V3.matcher(peerOnion).matches();
	}

	public HiddenServiceProperties start(@Nullable String privateKey)
			throws IOException, InterruptedException {
		synchronized (lifecycleLock) {
			return startLocked(privateKey);
		}
	}

	private HiddenServiceProperties startLocked(@Nullable String privateKey)
			throws IOException, InterruptedException {
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException("already started");
		}
		runGeneration.incrementAndGet();
		torDead.set(false);
		processWatch.setListener(this::onControlConnectionLost);
		try {
			startTor();
		} catch (IOException | InterruptedException e) {
			running.set(false);
			throw e;
		}
		try {
			startAccepting();
			startAcceptingAuthorized();
			HiddenServiceProperties hs = publishHiddenService(getOpenTarget(),
					REMOTE_ONION_PORT, privateKey);
			openOnion = hs.onion;
			return hs;
		} catch (IOException e) {
			closeListeners();
			try {
				tor.stop();
			} catch (IOException ignored) {
			}
			running.set(false);
			throw e;
		}
	}

	void startAcceptingAuthorized() throws IOException {
		OnionTargetListener l = targets.open();
		this.authorizedListener = l;
		this.authorizedTarget = l.getTorTarget();
		ioExecutor.execute(() -> acceptLoop(l, true, null));
	}

	public OnionTargetListener openServiceListener() throws IOException {
		return targets.open();
	}

	public String getAuthorizedTarget() {
		String t = authorizedTarget;
		if (t == null) throw new IllegalStateException("not accepting");
		return t;
	}

	public String getOpenTarget() {
		String t = openTarget;
		if (t == null) throw new IllegalStateException("not accepting");
		return t;
	}

	int getAuthorizedLocalPort() {
		String t = authorizedTarget;
		return t == null ? -1 : OnionTargets.loopbackPort(t);
	}

	public void setTorRestartedListener(@Nullable Runnable listener) {
		this.torRestartedListener = listener;
	}

	public void setTorReconfiguredListener(@Nullable Runnable listener) {
		this.torReconfiguredListener = listener;
	}

	private void torReconfigured() {
		if (!running.get()) return;
		Runnable listener = torReconfiguredListener;
		if (listener == null) return;
		try {
			listener.run();
		} catch (RuntimeException ignored) {
		}
	}

	private void startTor() throws IOException, InterruptedException {
		tor.start();
		try {
			tor.enableConnectionPadding(true);
			privacyConfigurator.applyAndVerify();
		} catch (IOException e) {
			try {
				tor.stop();
			} catch (IOException | InterruptedException ignored) {
			}
			throw e;
		}
		if (!bridgesApplied()) {
			try {
				tor.stop();
			} catch (IOException ignored) {
			}
			throw new IOException("bridge configuration failed");
		}
		if (!running.get()) {
			tor.stop();
			throw new IOException("stopped during start");
		}
		try {
			tor.enableNetwork(true);
		} catch (IOException e) {
			try {
				tor.stop();
			} catch (IOException | InterruptedException ignored) {
			}
			throw e;
		}
	}

	public HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException {
		return publishHiddenService(OnionTargets.loopback(localPort),
				remotePort, privateKey);
	}

	public HiddenServiceProperties publishHiddenService(String target,
			int remotePort, @Nullable String privateKey) throws IOException {
		synchronized (lifecycleLock) {
			HiddenServiceProperties hs = tor.publishHiddenService(target,
					remotePort, privateKey);
			if (hs == null) {
				throw new IOException("hidden service not published");
			}
			published.put(hs.onion,
					new PublishedService(target, remotePort, hs.privKey));
			return hs;
		}
	}

	public HiddenServiceProperties publishDedicatedService(
			@Nullable String privateKey) throws IOException {
		OnionTargetListener l = targets.open();
		HiddenServiceProperties hs;
		try {
			hs = publishHiddenService(l.getTorTarget(), REMOTE_ONION_PORT,
					privateKey);
		} catch (IOException | RuntimeException e) {
			closeQuietly(l);
			throw e;
		}
		OnionTargetListener previous = serviceListeners.put(hs.onion, l);
		if (previous != null) closeQuietly(previous);
		String onion = hs.onion;
		ioExecutor.execute(() -> acceptLoop(l, false, onion));
		return hs;
	}

	public void removeHiddenService(String onion) throws IOException {
		synchronized (lifecycleLock) {
			published.remove(onion);
			try {
				tor.removeHiddenService(onion);
			} finally {
				OnionTargetListener l = serviceListeners.remove(onion);
				if (l != null) closeQuietly(l);
			}
		}
	}

	List<String> publishedOnions() {
		synchronized (published) {
			return new ArrayList<>(published.keySet());
		}
	}

	void onControlConnectionLost() {
		if (!running.get()) return;
		if (!torDead.compareAndSet(false, true)) return;
		int run = runGeneration.get();
		ioExecutor.execute(() -> restartTorUntilUp(run));
	}

	private boolean isCurrentRun(int run) {
		return running.get() && runGeneration.get() == run;
	}

	boolean isTorDead() {
		return torDead.get();
	}

	private void restartTorUntilUp(int run) {
		long backoff = PROCESS_RESTART_BACKOFF_MIN_MS;
		while (isCurrentRun(run)) {
			try {
				if (restartTorOnce(run)) torDead.set(false);
				return;
			} catch (IOException e) {
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				sleeper.sleep(backoff);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			backoff = Math.min(backoff * 2, PROCESS_RESTART_BACKOFF_MAX_MS);
		}
	}

	private boolean restartTorOnce(int run)
			throws IOException, InterruptedException {
		synchronized (lifecycleLock) {
			if (!isCurrentRun(run)) return false;
			try {
				tor.stop();
			} catch (IOException ignored) {
			}
			startTor();
			List<PublishedService> services;
			synchronized (published) {
				services = new ArrayList<>(published.values());
			}
			for (PublishedService s : services) {
				HiddenServiceProperties hs = tor.publishHiddenService(s.target,
						s.remotePort, s.privateKey);
				if (hs == null) throw new IOException("republish failed");
			}
		}
		Runnable listener = torRestartedListener;
		if (listener != null && isCurrentRun(run)) listener.run();
		return true;
	}

	void startAccepting() throws IOException {
		OnionTargetListener l = targets.open();
		this.openListener = l;
		this.openTarget = l.getTorTarget();
		ioExecutor.execute(() -> acceptLoop(l, false, null));
	}

	private void acceptLoop(OnionTargetListener ss, boolean authorized,
			@Nullable String serviceOnion) {
		InboundBudget budget = authorized ? authorizedBudget : openBudget;
		while (!ss.isClosed()) {
			Socket socket;
			try {
				socket = ss.accept();
			} catch (IOException e) {
				if (ss.isClosed()) return;
				try {
					Thread.sleep(ACCEPT_RETRY_DELAY_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}
			if (!budget.inbound.tryAcquire()) {
				closeQuietly(socket);
				continue;
			}
			if (!budget.preTag.tryAcquire()) {
				budget.inbound.release();
				closeQuietly(socket);
				continue;
			}
			String localOnion = authorized ? null
					: serviceOnion != null ? serviceOnion : openOnion;
			try {
				ioExecutor.execute(() -> handleAccepted(socket, authorized,
						budget, localOnion));
			} catch (java.util.concurrent.RejectedExecutionException e) {
				budget.preTag.release();
				budget.inbound.release();
				closeQuietly(socket);
			}
		}
	}

	private void handleAccepted(Socket socket, boolean authorized,
			InboundBudget budget, @Nullable String localOnion) {
		java.util.concurrent.atomic.AtomicBoolean tagDelivered =
				new java.util.concurrent.atomic.AtomicBoolean(false);
		Runnable onTagDelivered = () -> {
			if (tagDelivered.compareAndSet(false, true)) {
				budget.preTag.release();
				try {
					socket.setSoTimeout(SOCKET_TIMEOUT_MS);
				} catch (java.net.SocketException ignored) {
				}
			}
		};
		try {
			socket.setSoTimeout(TAG_READ_TIMEOUT_MS);
			try {
				socket.setTcpNoDelay(true);
			} catch (java.net.SocketException ignored) {
			}
			InputStream in = new PreambleDeadlineInputStream(
					socket.getInputStream(), TAG_LENGTH, onTagDelivered,
					clock, TAG_READ_TIMEOUT_MS);
			handler.handleIncoming(TorConstants.ID, in,
					socket.getOutputStream(), authorized, localOnion);
		} catch (IOException e) {
		} finally {
			closeQuietly(socket);
			if (tagDelivered.compareAndSet(false, true)) {
				budget.preTag.release();
			}
			budget.inbound.release();
		}
	}

	static final class PreambleDeadlineInputStream
			extends java.io.FilterInputStream {

		private final int preambleLength;
		private final Runnable onPreambleDelivered;
		private final LongSupplier clock;
		private final long deadlineMs;
		private final long startedMs;
		private long delivered = 0;
		private boolean signalled = false;

		PreambleDeadlineInputStream(InputStream in, int preambleLength,
				Runnable onPreambleDelivered, LongSupplier clock,
				long deadlineMs) {
			super(in);
			this.preambleLength = preambleLength;
			this.onPreambleDelivered = onPreambleDelivered;
			this.clock = clock;
			this.deadlineMs = deadlineMs;
			this.startedMs = clock.getAsLong();
		}

		private void checkDeadline() throws IOException {
			if (!signalled && clock.getAsLong() - startedMs > deadlineMs) {
				throw new IOException("preamble deadline passed");
			}
		}

		@Override
		public int read() throws IOException {
			checkDeadline();
			int b = in.read();
			if (b >= 0) count(1);
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			checkDeadline();
			int n = in.read(b, off, len);
			if (n > 0) count(n);
			return n;
		}

		private void count(int n) {
			delivered += n;
			if (!signalled && delivered >= preambleLength) {
				signalled = true;
				onPreambleDelivered.run();
			}
		}
	}

	@Override
	public DialResult dial(int contactId, String peerOnion, boolean fast) {
		return dial(contactId, peerOnion, fast, false);
	}

	@Override
	public DialResult dial(int contactId, String peerOnion, boolean fast,
			boolean refusalExpected) {
		if (!isValidAddress(peerOnion)) return DialResult.NOT_CONNECTED;
		if (torDead.get() || tor.getTorState() != TorState.CONNECTED) {
			return DialResult.NOT_CONNECTED;
		}
		SocketFactory factory = fast ? fastSocketFactory : socketFactory;
		Socket socket;
		try {
			socket = factory.createSocket(peerOnion + ".onion",
					REMOTE_ONION_PORT);
		} catch (IOException e) {
			return DialResult.NOT_CONNECTED;
		}
		long connectedAt = System.currentTimeMillis();
		DialListener listener = dialListener;
		if (listener != null) listener.dialSucceeded(contactId, peerOnion);
		boolean authenticated = false;
		try {
			configureSocket(socket);
			authenticated = handler.handleOutgoingSession(TorConstants.ID,
					contactId, socket.getInputStream(),
					socket.getOutputStream(), refusalExpected);
		} catch (IOException e) {
		} finally {
			closeQuietly(socket);
		}
		return new DialResult(authenticated,
				System.currentTimeMillis() - connectedAt);
	}

	private static void configureSocket(Socket socket) throws IOException {
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		try {
			socket.setTcpNoDelay(true);
		} catch (java.net.SocketException ignored) {
		}
	}

	public int getLocalPort() {
		String t = openTarget;
		return t == null ? -1 : OnionTargets.loopbackPort(t);
	}

	void onTorState(TorState state) {
		if (state == TorState.CONNECTED) {
			everConnected.set(true);
			degradedSinceMs = 0;
			if (republishOnConnect) {
				republishOnConnect = false;
				ioExecutor.execute(this::republishHiddenServices);
			}
		} else if (state == TorState.CONNECTING) {
			if (everConnected.get() && degradedSinceMs == 0) {
				degradedSinceMs = clock.getAsLong();
			}
		} else {
			degradedSinceMs = 0;
		}
	}

	@Override
	public boolean isNetworkDegraded() {
		long since = degradedSinceMs;
		return running.get() && since != 0
				&& clock.getAsLong() - since >= DEGRADED_GRACE_MS;
	}

	@Override
	public void setNetworkEnabled(boolean enabled) {
		if (!running.get()) return;
		if (!enabled) republishOnConnect = true;
		if (enabled && !bridgesApplied()) {
			try {
				tor.enableNetwork(false);
			} catch (IOException e) {
			}
			torReconfigured();
			return;
		}
		if (enabled && isNetworkDegraded() && restartNetworkNow()) return;
		try {
			tor.enableNetwork(enabled);
		} catch (IOException e) {
		}
		torReconfigured();
	}

	private boolean bridgesApplied() {
		TorBridgeConfigurator b = bridgeConfigurator;
		return b == null || b.apply();
	}

	@Override
	public void restartNetwork() {
		if (!running.get()) return;
		restartNetworkNow();
	}

	void republishHiddenServices() {
		if (!running.get() || torDead.get()) return;
		synchronized (lifecycleLock) {
			if (!running.get() || torDead.get()) return;
			List<Map.Entry<String, PublishedService>> services;
			synchronized (published) {
				services = new ArrayList<>(published.entrySet());
			}
			for (Map.Entry<String, PublishedService> e : services) {
				PublishedService svc = e.getValue();
				try {
					tor.removeHiddenService(e.getKey());
				} catch (IOException ignored) {
				}
				try {
					tor.publishHiddenService(svc.target, svc.remotePort,
							svc.privateKey);
				} catch (IOException ignored) {
				}
			}
		}
	}

	@Override
	public void refreshPeerDescriptors() {
		if (!running.get() || torDead.get()) return;
		if (tor.getTorState() != TorState.CONNECTED) return;
		try {
			tor.forgetHiddenServiceDescriptors();
		} catch (IOException e) {
			return;
		}
		torReconfigured();
	}

	private boolean restartNetworkNow() {
		synchronized (restartLock) {
			if (tor.getTorState() != TorState.CONNECTING) return false;
			long now = clock.getAsLong();
			if (now - lastRestartMs < MIN_RESTART_INTERVAL_MS) return false;
			lastRestartMs = now;
		}
		republishOnConnect = true;
		try {
			tor.enableNetwork(false);
			if (bridgesApplied()) tor.enableNetwork(true);
		} catch (IOException e) {
		}
		torReconfigured();
		return true;
	}

	public void stop() throws IOException, InterruptedException {
		running.set(false);
		synchronized (lifecycleLock) {
			processWatch.setListener(null);
			closeListeners();
			published.clear();
			tor.stop();
		}
	}

	private void closeListeners() {
		OnionTargetListener open = openListener;
		if (open != null) closeQuietly(open);
		OnionTargetListener authorized = authorizedListener;
		if (authorized != null) closeQuietly(authorized);
		for (OnionTargetListener l : serviceListeners.values()) {
			closeQuietly(l);
		}
		serviceListeners.clear();
		openOnion = null;
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}
}
