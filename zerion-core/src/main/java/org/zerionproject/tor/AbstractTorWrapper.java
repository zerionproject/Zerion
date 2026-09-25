package org.zerionproject.tor;

import net.freehaven.tor.control.EventHandler;
import net.freehaven.tor.control.TorControlConnection;

import org.briarproject.nullsafety.InterfaceNotNullByDefault;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static net.freehaven.tor.control.TorControlCommands.HS_ADDRESS;
import static net.freehaven.tor.control.TorControlCommands.HS_PRIVKEY;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.zerionproject.tor.TorUtils.UTF_8;
import static org.zerionproject.tor.TorUtils.copyAndClose;
import static org.zerionproject.tor.TorUtils.tryToClose;
import static org.zerionproject.tor.TorWrapper.TorState.CONNECTED;
import static org.zerionproject.tor.TorWrapper.TorState.CONNECTING;
import static org.zerionproject.tor.TorWrapper.TorState.DISABLED;
import static org.zerionproject.tor.TorWrapper.TorState.NOT_STARTED;
import static org.zerionproject.tor.TorWrapper.TorState.STARTED;
import static org.zerionproject.tor.TorWrapper.TorState.STARTING;
import static org.zerionproject.tor.TorWrapper.TorState.STOPPED;
import static org.zerionproject.tor.TorWrapper.TorState.STOPPING;

/**
 * Runs Tor as a child process from a configuration written at every start,
 * authenticates to its control port with the cookie it creates, and tracks
 * its state from the control events. Derived from the Briar Project's
 * onion wrapper 0.1.4 (GPLv3) with these changes: the executables are
 * verified against build-time pins before they run, the SOCKS listener is
 * isolated per client and destination from the first line of the
 * configuration, connection padding is on from the start, a process that
 * does not exit when told to is killed within a bound instead of waited
 * for without one, a process that never opens its control listener is
 * given up on, a start interrupted midway leaves no orphan process, and
 * nothing is logged. The cookie is read only after Tor has reported its
 * control listener open, by which time Tor has written a fresh one, so a
 * stale cookie file that could not be deleted is never the one read.
 */
@InterfaceNotNullByDefault
abstract class AbstractTorWrapper implements EventHandler, TorWrapper {

	private static final String[] EVENTS = {
			"CIRC",
			"ORCONN",
			"STATUS_GENERAL",
			"STATUS_CLIENT",
			"HS_DESC",
			"NOTICE",
			"WARN",
			"ERR"
	};

	private static final String OWNER = "__OwningControllerProcess";
	private static final int COOKIE_TIMEOUT_MS = 3000;
	private static final int COOKIE_POLLING_INTERVAL_MS = 200;
	private static final Pattern BOOTSTRAP_PERCENTAGE =
			Pattern.compile(".*PROGRESS=(\\d{1,3}).*");

	/** How long a started process gets to open its control listener. */
	static final long START_TIMEOUT_MS = 30_000;

	/** How long a process gets to exit after being told to shut down. */
	static final long EXIT_TIMEOUT_MS = 10_000;

	/** How long a process gets to die after being killed. */
	static final long KILL_TIMEOUT_MS = 5_000;

	static final long EXIT_POLL_INTERVAL_MS = 50;

	/**
	 * The isolation flags every SOCKS listener of this process carries.
	 * IsolateSOCKSAuth makes the credentials the socket factory presents
	 * part of the circuit key, the other two isolate by client address and
	 * by destination regardless of credentials.
	 */
	static final String[] SOCKS_ISOLATION_FLAGS = {
			"IsolateSOCKSAuth", "IsolateClientAddr", "IsolateDestAddr"
	};

	protected final Executor ioExecutor;
	protected final Executor eventExecutor;
	protected final String architecture;
	protected final File torDirectory;
	private final File configFile, doneFile, cookieFile;
	private final int torSocksPort;
	private final int torControlPort;
	private final TorBinaryVerifier verifier;

	protected final NetworkState state = new NetworkState();

	@Nullable
	private volatile Process torProcess = null;
	@Nullable
	private volatile Socket controlSocket = null;
	@Nullable
	private volatile TorControlConnection controlConnection = null;

	protected abstract int getProcessId();

	protected abstract long getLastUpdateTime();

	protected abstract InputStream getResourceInputStream(String name,
			String extension);

	AbstractTorWrapper(Executor ioExecutor, Executor eventExecutor,
			String architecture, File torDirectory, int torSocksPort,
			int torControlPort, TorBinaryVerifier verifier) {
		this.ioExecutor = ioExecutor;
		this.eventExecutor = eventExecutor;
		this.architecture = architecture;
		this.torDirectory = torDirectory;
		this.torSocksPort = torSocksPort;
		this.torControlPort = torControlPort;
		this.verifier = verifier;
		configFile = new File(torDirectory, "torrc");
		doneFile = new File(torDirectory, "done");
		cookieFile = new File(torDirectory, ".tor/control_auth_cookie");
	}

	protected File getTorExecutableFile() {
		return new File(torDirectory, "tor");
	}

	@Override
	public File getLyrebirdExecutableFile() {
		return new File(torDirectory, "lyrebird");
	}

	@Override
	public void setObserver(@Nullable Observer observer) {
		state.setObserver(observer);
	}

	@Override
	public void start() throws IOException, InterruptedException {
		if (!state.setStarting()) return;
		Process process = null;
		try {
			if (!torDirectory.exists() && !torDirectory.mkdirs()) {
				throw new IOException("Could not create Tor directory");
			}
			if (!assetsAreUpToDate()) installAssets();
			extract(getConfigInputStream(), configFile);
			cookieFile.delete();

			File torFile = getTorExecutableFile();
			verifier.verify(torFile, getLyrebirdExecutableFile());

			ProcessBuilder pb = new ProcessBuilder(torFile.getAbsolutePath(),
					"-f", configFile.getAbsolutePath(), OWNER,
					String.valueOf(getProcessId()));
			pb.environment().put("HOME", torDirectory.getAbsolutePath());
			pb.directory(torDirectory);
			pb.redirectErrorStream(true);
			try {
				process = pb.start();
			} catch (SecurityException e) {
				throw new IOException(e);
			}
			torProcess = process;

			waitForTorToStart(process, START_TIMEOUT_MS);

			long start = System.currentTimeMillis();
			while (cookieFile.length() < 32) {
				if (System.currentTimeMillis() - start > COOKIE_TIMEOUT_MS) {
					throw new IOException("Auth cookie not created");
				}
				Thread.sleep(COOKIE_POLLING_INTERVAL_MS);
			}

			Socket socket = new Socket("127.0.0.1", torControlPort);
			controlSocket = socket;
			TorControlConnection connection = new TorControlConnection(socket);
			controlConnection = connection;
			connection.authenticate(read(cookieFile));

			connection.takeOwnership();
			connection.resetConf(singletonList(OWNER));

			connection.setEventHandler(this);
			connection.setEvents(asList(EVENTS));

			String info = connection.getInfo("status/bootstrap-phase");
			if (info != null && info.contains("PROGRESS=")) {
				state.setBootstrapPercentage(parseBootstrapPercentage(info));
			}
			info = connection.getInfo("status/circuit-established");
			if ("1".equals(info)) state.setCircuitBuilt(true);
		} catch (IOException | InterruptedException | RuntimeException e) {
			abandonStart(process);
			throw e;
		}
		state.setStarted();
	}

	/**
	 * Undoes a start that failed at any point: the control connection is
	 * dropped, the process, if one was launched, is terminated within the
	 * bound, and the state returns to stopped so that the wrapper can be
	 * started again and no Tor process outlives the attempt.
	 */
	private void abandonStart(@Nullable Process process)
			throws InterruptedException {
		closeControl();
		try {
			if (process != null) terminate(process);
		} finally {
			torProcess = null;
			state.setStartupFailed();
		}
	}

	private void closeControl() {
		controlConnection = null;
		tryToClose(controlSocket);
		controlSocket = null;
	}

	private boolean assetsAreUpToDate() {
		return doneFile.lastModified() > getLastUpdateTime();
	}

	private void installAssets() throws IOException {
		doneFile.delete();
		installTorExecutable();
		installLyrebirdExecutable();
		extract(getConfigInputStream(), configFile);
		doneFile.createNewFile();
	}

	protected void extract(InputStream in, File dest) throws IOException {
		OutputStream out = new FileOutputStream(dest);
		copyAndClose(in, out);
	}

	protected void installTorExecutable() throws IOException {
		File torFile = getTorExecutableFile();
		extract(getExecutableInputStream("tor"), torFile);
		if (!torFile.setExecutable(true, true)) throw new IOException();
	}

	protected void installLyrebirdExecutable() throws IOException {
		File lyrebirdFile = getLyrebirdExecutableFile();
		extract(getExecutableInputStream("lyrebird"), lyrebirdFile);
		if (!lyrebirdFile.setExecutable(true, true)) throw new IOException();
	}

	protected InputStream getExecutableInputStream(String basename) {
		String ext = getExecutableExtension();
		return requireNonNull(getResourceInputStream(
				architecture + "/" + basename, ext));
	}

	protected String getExecutableExtension() {
		return "";
	}

	private static void append(StringBuilder strb, String name,
			Object value) {
		strb.append(name);
		strb.append(" ");
		strb.append(value);
		strb.append("\n");
	}

	/**
	 * The configuration Tor is started with. The network is disabled until
	 * the transport has verified the listener it needs, the SOCKS listener
	 * is isolated from its first line, padding is on, and the pluggable
	 * transports point at the verified lyrebird executable.
	 */
	String torrc() {
		File dataDirectory = new File(torDirectory, ".tor");
		StringBuilder strb = new StringBuilder();
		append(strb, "ControlPort", torControlPort);
		append(strb, "CookieAuthentication", 1);
		append(strb, "DataDirectory", dataDirectory.getAbsolutePath());
		append(strb, "DisableNetwork", 1);
		append(strb, "SafeSocks", 1);
		strb.append("SocksPort ").append(torSocksPort);
		for (String flag : SOCKS_ISOLATION_FLAGS) {
			strb.append(' ').append(flag);
		}
		strb.append('\n');
		strb.append("GeoIPFile\n");
		strb.append("GeoIPv6File\n");
		append(strb, "ConnectionPadding", 1);
		append(strb, "ReducedConnectionPadding", 0);
		String lyrebirdPath = getLyrebirdExecutableFile().getAbsolutePath();
		append(strb, "ClientTransportPlugin obfs4 exec", lyrebirdPath);
		append(strb, "ClientTransportPlugin meek_lite exec", lyrebirdPath);
		append(strb, "ClientTransportPlugin snowflake exec", lyrebirdPath);
		return strb.toString();
	}

	private InputStream getConfigInputStream() {
		return new ByteArrayInputStream(torrc().getBytes(UTF_8));
	}

	private byte[] read(File f) throws IOException {
		byte[] b = new byte[(int) f.length()];
		FileInputStream in = new FileInputStream(f);
		try {
			int offset = 0;
			while (offset < b.length) {
				int read = in.read(b, offset, b.length - offset);
				if (read == -1) throw new EOFException();
				offset += read;
			}
			return b;
		} finally {
			tryToClose(in);
		}
	}

	/**
	 * Reads the process's output on the IO executor until it reports that
	 * the control listener is open, and keeps reading for the life of the
	 * process so that its output never fills the pipe. Fails if the
	 * process exits first or says nothing within the timeout.
	 */
	void waitForTorToStart(Process torProcess, long timeoutMs)
			throws InterruptedException, IOException {
		BlockingQueue<Boolean> success = new ArrayBlockingQueue<>(1);
		ioExecutor.execute(() -> {
			boolean started = false;
			try (Scanner stdout = new Scanner(torProcess.getInputStream())) {
				while (stdout.hasNextLine()) {
					String line = stdout.nextLine();
					if (!started && line.contains("Opened Control listener")) {
						success.offer(true);
						started = true;
					}
				}
			}
			if (!started) success.offer(false);
			try {
				torProcess.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		Boolean started = success.poll(timeoutMs, MILLISECONDS);
		if (started == null) throw new IOException("Tor did not start in time");
		if (!started) throw new IOException("Tor exited before starting");
	}

	@Override
	public HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privKey) throws IOException {
		Map<Integer, String> portLines =
				singletonMap(remotePort, "127.0.0.1:" + localPort);
		Map<String, String> response;
		if (privKey == null) {
			response = getControlConnection()
					.addOnion("NEW:ED25519-V3", portLines, null);
		} else {
			response = getControlConnection().addOnion(privKey, portLines);
		}
		if (!response.containsKey(HS_ADDRESS)) {
			throw new IOException("Missing hidden service address");
		}
		if (privKey == null && !response.containsKey(HS_PRIVKEY)) {
			throw new IOException("Missing private key");
		}
		String onion = response.get(HS_ADDRESS);
		if (privKey == null) privKey = response.get(HS_PRIVKEY);
		return new HiddenServiceProperties(onion, privKey);
	}

	@Override
	public void removeHiddenService(String onion) throws IOException {
		getControlConnection().delOnion(onion);
	}

	@Override
	public void enableNetwork(boolean enable) throws IOException {
		if (!state.enableNetwork(enable)) return;
		getControlConnection().setConf("DisableNetwork", enable ? "0" : "1");
	}

	@Override
	public void enableBridges(List<String> bridges) throws IOException {
		if (!state.setBridges(bridges)) return;
		if (bridges.isEmpty()) {
			throw new IllegalArgumentException("Bridges can't be empty.");
		}
		List<String> conf = new ArrayList<>(bridges.size() + 1);
		conf.add("UseBridges 1");
		conf.addAll(bridges);
		getControlConnection().setConf(conf);
	}

	@Override
	public void disableBridges() throws IOException {
		if (!state.setBridges(emptyList())) return;
		getControlConnection().setConf("UseBridges", "0");
	}

	@Override
	public void stop() throws IOException, InterruptedException {
		if (!state.setStopping()) return;
		Process process = torProcess;
		try {
			TorControlConnection connection = controlConnection;
			if (connection != null) connection.shutdownTor("TERM");
		} finally {
			closeControl();
			try {
				if (process != null) terminate(process);
			} finally {
				torProcess = null;
				state.setStopped();
			}
		}
	}

	/**
	 * Waits within {@link #EXIT_TIMEOUT_MS} for a process that has been
	 * asked to shut down, kills it if it is still alive, and waits within
	 * {@link #KILL_TIMEOUT_MS} for the kill to take effect. Returns true if
	 * the process exited on its own.
	 */
	static boolean terminate(Process process) throws InterruptedException {
		if (awaitExit(process, EXIT_TIMEOUT_MS)) return true;
		process.destroy();
		if (awaitExit(process, KILL_TIMEOUT_MS)) return false;
		destroyForcibly(process);
		awaitExit(process, KILL_TIMEOUT_MS);
		return false;
	}

	/**
	 * A plain destroy already kills on Android; on a JVM it only asks. The
	 * forcible variant exists on every runtime this code compiles against
	 * but not on the oldest Android releases the app supports, so it is
	 * reached by reflection and skipped where it is absent.
	 */
	private static void destroyForcibly(Process process) {
		try {
			Method m = Process.class.getMethod("destroyForcibly");
			m.invoke(process);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
		}
	}

	/**
	 * True once the process has exited, false if it is still alive when
	 * the timeout passes. Polls, because a bounded wait is not available on
	 * every runtime the app supports.
	 */
	static boolean awaitExit(Process process, long timeoutMs)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (true) {
			try {
				process.exitValue();
				return true;
			} catch (IllegalThreadStateException stillRunning) {
				long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) return false;
				Thread.sleep(Math.min(EXIT_POLL_INTERVAL_MS, remaining));
			}
		}
	}

	@Override
	public void circuitStatus(String status, String id, String path) {
		if (status.equals("BUILT")) state.setCircuitBuilt(true);
	}

	@Override
	public void streamStatus(String status, String id, String target) {
	}

	@Override
	public void orConnStatus(String status, String orName) {
		if (status.equals("CONNECTED")) state.onOrConnectionConnected();
		else if (status.equals("CLOSED")) state.onOrConnectionClosed();
	}

	@Override
	public void bandwidthUsed(long read, long written) {
	}

	@Override
	public void newDescriptors(List<String> orList) {
	}

	@Override
	public void message(String severity, String msg) {
	}

	@Override
	public void unrecognized(String type, String msg) {
		if (type.equals("STATUS_CLIENT")) {
			handleClientStatus(removeSeverity(msg));
		} else if (type.equals("STATUS_GENERAL")) {
			handleGeneralStatus(removeSeverity(msg));
		} else if (type.equals("HS_DESC") && msg.startsWith("UPLOADED")) {
			String[] parts = msg.split(" ");
			if (parts.length >= 2) state.onHsDescriptorUploaded(parts[1]);
		}
	}

	private String removeSeverity(String msg) {
		return msg.replaceFirst("[^ ]+ ", "");
	}

	private void handleClientStatus(String msg) {
		if (msg.startsWith("BOOTSTRAP PROGRESS=")) {
			state.setBootstrapPercentage(parseBootstrapPercentage(msg));
		} else if (msg.startsWith("CIRCUIT_ESTABLISHED")) {
			state.setCircuitBuilt(true);
		} else if (msg.startsWith("CIRCUIT_NOT_ESTABLISHED")) {
			state.setCircuitBuilt(false);
		}
	}

	private int parseBootstrapPercentage(String s) {
		Matcher matcher = BOOTSTRAP_PERCENTAGE.matcher(s);
		if (matcher.matches()) {
			try {
				return Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException ignored) {
			}
		}
		return 0;
	}

	private void handleGeneralStatus(String msg) {
		if (msg.startsWith("CLOCK_SKEW")) {
			Long skew = parseLongArgument(msg, "SKEW");
			if (skew != null) state.onClockSkewDetected(skew);
		}
	}

	@Nullable
	private Long parseLongArgument(String msg, String argName) {
		String[] args = msg.split(" ");
		for (String arg : args) {
			if (arg.startsWith(argName + "=")) {
				try {
					return Long.parseLong(arg.substring(argName.length() + 1));
				} catch (NumberFormatException e) {
					return null;
				}
			}
		}
		return null;
	}

	@Override
	public void controlConnectionClosed() {
	}

	@Override
	public void enableConnectionPadding(boolean enable) throws IOException {
		if (!state.enableConnectionPadding(enable)) return;
		getControlConnection().setConf("ConnectionPadding", enable ? "1" : "0");
	}

	@Override
	public void enableIpv6(boolean enable) throws IOException {
		if (!state.enableIpv6(enable)) return;
		getControlConnection().setConf("ClientUseIPv4", enable ? "0" : "1");
		getControlConnection().setConf("ClientUseIPv6", enable ? "1" : "0");
	}

	@Override
	public TorState getTorState() {
		return state.getState();
	}

	@Override
	public boolean isTorRunning() {
		return state.isTorRunning();
	}

	private TorControlConnection getControlConnection() throws IOException {
		TorControlConnection connection = this.controlConnection;
		if (connection == null) {
			throw new IOException("Control connection not opened");
		}
		return connection;
	}

	private enum ProcessState {
		NOT_STARTED, STARTING, STARTED, STOPPING, STOPPED
	}

	@ThreadSafe
	@NotNullByDefault
	private class NetworkState {

		@GuardedBy("this")
		@Nullable
		private Observer observer = null;

		@GuardedBy("this")
		private ProcessState processState = ProcessState.NOT_STARTED;

		@GuardedBy("this")
		private boolean networkInitialised = false,
				networkEnabled = false,
				paddingEnabled = true,
				ipv6Enabled = false,
				circuitBuilt = false;

		@GuardedBy("this")
		private int bootstrapPercentage = 0;

		@GuardedBy("this")
		private List<String> bridges = emptyList();

		@GuardedBy("this")
		private int orConnectionsConnected = 0;

		@GuardedBy("this")
		@Nullable
		private TorState state = null;

		private synchronized void setObserver(@Nullable Observer observer) {
			this.observer = observer;
		}

		@GuardedBy("this")
		private void updateState() {
			TorState newState = getState();
			if (newState != state) {
				state = newState;
				if (observer != null) {
					Observer o = observer;
					eventExecutor.execute(() -> o.onState(newState));
				}
			}
		}

		private synchronized boolean setStarting() {
			if (processState != ProcessState.NOT_STARTED
					&& processState != ProcessState.STOPPED) {
				return false;
			}
			processState = ProcessState.STARTING;
			updateState();
			return true;
		}

		private synchronized void setStarted() {
			if (processState != ProcessState.STARTING) {
				throw new IllegalStateException();
			}
			processState = ProcessState.STARTED;
			updateState();
		}

		private synchronized void setStartupFailed() {
			if (processState != ProcessState.STARTING) {
				throw new IllegalStateException();
			}
			processState = ProcessState.STOPPED;
			reset();
			updateState();
		}

		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private synchronized boolean isTorRunning() {
			return processState == ProcessState.STARTED;
		}

		private synchronized boolean setStopping() {
			if (processState != ProcessState.STARTED) return false;
			processState = ProcessState.STOPPING;
			updateState();
			return true;
		}

		private synchronized void setStopped() {
			if (processState != ProcessState.STOPPING) {
				throw new IllegalStateException();
			}
			processState = ProcessState.STOPPED;
			reset();
			updateState();
		}

		@GuardedBy("this")
		private void reset() {
			networkInitialised = false;
			networkEnabled = false;
			paddingEnabled = true;
			ipv6Enabled = false;
			circuitBuilt = false;
			bootstrapPercentage = 0;
			bridges = emptyList();
			orConnectionsConnected = 0;
		}

		private synchronized void setBootstrapPercentage(int percentage) {
			if (percentage == bootstrapPercentage) return;
			bootstrapPercentage = percentage;
			if (observer != null) {
				Observer o = observer;
				eventExecutor.execute(() -> o.onBootstrapPercentage(percentage));
			}
			updateState();
		}

		private synchronized boolean setCircuitBuilt(boolean built) {
			if (built == circuitBuilt) return false;
			circuitBuilt = built;
			updateState();
			return true;
		}

		private synchronized boolean enableNetwork(boolean enable) {
			boolean wasInitialised = networkInitialised;
			boolean wasEnabled = networkEnabled;
			networkInitialised = true;
			networkEnabled = enable;
			if (!enable) circuitBuilt = false;
			if (!wasInitialised || enable != wasEnabled) updateState();
			return enable != wasEnabled;
		}

		private synchronized boolean enableConnectionPadding(boolean enable) {
			if (enable == paddingEnabled) return false;
			paddingEnabled = enable;
			return true;
		}

		private synchronized boolean enableIpv6(boolean enable) {
			if (enable == ipv6Enabled) return false;
			ipv6Enabled = enable;
			return true;
		}

		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private synchronized boolean setBridges(List<String> bridges) {
			if (this.bridges.equals(bridges)) return false;
			this.bridges = bridges;
			return true;
		}

		private synchronized TorState getState() {
			if (processState == ProcessState.NOT_STARTED) return NOT_STARTED;
			if (processState == ProcessState.STARTING) return STARTING;
			if (processState == ProcessState.STOPPING) return STOPPING;
			if (processState == ProcessState.STOPPED) return STOPPED;
			if (!networkInitialised) return STARTED;
			if (!networkEnabled) return DISABLED;
			return bootstrapPercentage == 100 && circuitBuilt
					&& orConnectionsConnected > 0 ? CONNECTED : CONNECTING;
		}

		private synchronized void onOrConnectionConnected() {
			int oldConnected = orConnectionsConnected;
			orConnectionsConnected++;
			if (oldConnected == 0) updateState();
		}

		private synchronized void onOrConnectionClosed() {
			int oldConnected = orConnectionsConnected;
			orConnectionsConnected--;
			if (orConnectionsConnected < 0) orConnectionsConnected = 0;
			if (orConnectionsConnected == 0 && oldConnected != 0) {
				updateState();
			}
		}

		private synchronized void onHsDescriptorUploaded(String onion) {
			if (observer != null) {
				Observer o = observer;
				eventExecutor.execute(() -> o.onHsDescriptorUpload(onion));
			}
		}

		private synchronized void onClockSkewDetected(long skewSeconds) {
			if (observer != null) {
				Observer o = observer;
				eventExecutor.execute(() -> o.onClockSkewDetected(skewSeconds));
			}
		}
	}
}
