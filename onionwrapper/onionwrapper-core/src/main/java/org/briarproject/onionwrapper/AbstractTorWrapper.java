package org.briarproject.onionwrapper;

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
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static net.freehaven.tor.control.TorControlCommands.HS_ADDRESS;
import static net.freehaven.tor.control.TorControlCommands.HS_PRIVKEY;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.briarproject.onionwrapper.TorUtils.UTF_8;
import static org.briarproject.onionwrapper.TorUtils.copyAndClose;
import static org.briarproject.onionwrapper.TorUtils.scrubOnion;
import static org.briarproject.onionwrapper.TorUtils.tryToClose;
import static org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.CONNECTING;
import static org.briarproject.onionwrapper.TorWrapper.TorState.DISABLED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.NOT_STARTED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.STARTED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.STARTING;
import static org.briarproject.onionwrapper.TorWrapper.TorState.STOPPED;
import static org.briarproject.onionwrapper.TorWrapper.TorState.STOPPING;

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
	private static final Pattern BOOTSTRAP_PERCENTAGE = Pattern.compile(".*PROGRESS=(\\d{1,3}).*");

	protected final Executor ioExecutor;
	protected final Executor eventExecutor;
	protected final String architecture;
	protected final File torDirectory;
	private final File configFile, doneFile, cookieFile;
	private final int torSocksPort;
	private final int torControlPort;

	protected final NetworkState state = new NetworkState();

	private volatile Process torProcess = null;
	private volatile Socket controlSocket = null;
	private volatile TorControlConnection controlConnection = null;

	protected abstract int getProcessId();

	protected abstract long getLastUpdateTime();

	protected abstract InputStream getResourceInputStream(String name, String extension);

	AbstractTorWrapper(Executor ioExecutor,
			Executor eventExecutor,
			String architecture,
			File torDirectory,
			int torSocksPort,
			int torControlPort) {
		this.ioExecutor = ioExecutor;
		this.eventExecutor = eventExecutor;
		this.architecture = architecture;
		this.torDirectory = torDirectory;
		this.torSocksPort = torSocksPort;
		this.torControlPort = torControlPort;
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
		if (!state.setStarting()) return; // Not in the appropriate state
		try {
			if (!torDirectory.exists()) {
				if (!torDirectory.mkdirs()) {
					throw new IOException("Could not create Tor directory");
				}
			}
			// Install or update the assets if necessary
			if (!assetsAreUpToDate()) installAssets();
			// Start from the default config every time
			extract(getConfigInputStream(), configFile);
			if (cookieFile.exists() && !cookieFile.delete()) {
				LOG.warning("Old auth cookie not deleted");
			}
			// Start a new Tor process
			LOG.info("Starting Tor");
			File torFile = getTorExecutableFile();
			String torPath = torFile.getAbsolutePath();
			String configPath = configFile.getAbsolutePath();
			String pid = String.valueOf(getProcessId());
			ProcessBuilder pb = new ProcessBuilder(torPath, "-f", configPath, OWNER, pid);
			Map<String, String> env = pb.environment();
			env.put("HOME", torDirectory.getAbsolutePath());
			pb.directory(torDirectory);
			pb.redirectErrorStream(true);
			try {
				torProcess = pb.start();
			} catch (SecurityException e) {
				throw new IOException(e);
			}
			// Wait for the Tor process to start
			waitForTorToStart(requireNonNull(torProcess));
			// Wait for the auth cookie file to be created/updated
			long start = System.currentTimeMillis();
			while (cookieFile.length() < 32) {
				if (System.currentTimeMillis() - start > COOKIE_TIMEOUT_MS) {
					throw new IOException("Auth cookie not created");
				}
				//noinspection BusyWait
				Thread.sleep(COOKIE_POLLING_INTERVAL_MS);
			}
			LOG.info("Auth cookie created");
			// Open a control connection and authenticate using the cookie file
			controlSocket = new Socket("127.0.0.1", torControlPort);
			controlConnection = new TorControlConnection(controlSocket);
			controlConnection.authenticate(read(cookieFile));
			// Tell Tor to exit when the control connection is closed
			controlConnection.takeOwnership();
			controlConnection.resetConf(singletonList(OWNER));
			// Register to receive events from the Tor process
			controlConnection.setEventHandler(this);
			controlConnection.setEvents(asList(EVENTS));
			// Check whether Tor has already bootstrapped
			String info = controlConnection.getInfo("status/bootstrap-phase");
			if (info != null && info.contains("PROGRESS=")) {
				int percentage = parseBootstrapPercentage(info);
				if (percentage == 100) LOG.info("Tor has already bootstrapped");
				state.setBootstrapPercentage(percentage);
			}
			// Check whether Tor has already built a circuit
			info = controlConnection.getInfo("status/circuit-established");
			if ("1".equals(info)) {
				LOG.info("Tor has already built a circuit");
				state.setCircuitBuilt(true);
			}
		} catch (IOException e) {
			// Clean up
			if (controlSocket != null) {
				tryToClose(controlSocket, LOG, WARNING);
				controlSocket = null;
				controlConnection = null;
			}
			if (torProcess != null) {
				torProcess.destroy();
				torProcess.waitFor();
				torProcess = null;
			}
			state.setStartupFailed();
			throw e;
		}
		state.setStarted();
	}

	private boolean assetsAreUpToDate() {
		return doneFile.lastModified() > getLastUpdateTime();
	}

	private void installAssets() throws IOException {
		// The done file may already exist from a previous installation
		//noinspection ResultOfMethodCallIgnored
		doneFile.delete();
		installTorExecutable();
		installLyrebirdExecutable();
		extract(getConfigInputStream(), configFile);
		if (!doneFile.createNewFile()) {
			LOG.warning("Failed to create done file");
		}
	}

	protected void extract(InputStream in, File dest) throws IOException {
		@SuppressWarnings("IOStreamConstructor")
		OutputStream out = new FileOutputStream(dest);
		copyAndClose(in, out);
	}

	protected void installTorExecutable() throws IOException {
		if (LOG.isLoggable(INFO)) {
			LOG.info("Installing Tor binary for " + architecture);
		}
		File torFile = getTorExecutableFile();
		extract(getExecutableInputStream("tor"), torFile);
		if (!torFile.setExecutable(true, true)) throw new IOException();
	}

	protected void installLyrebirdExecutable() throws IOException {
		if (LOG.isLoggable(INFO)) {
			LOG.info("Installing lyrebird binary for " + architecture);
		}
		File lyrebirdFile = getLyrebirdExecutableFile();
		extract(getExecutableInputStream("lyrebird"), lyrebirdFile);
		if (!lyrebirdFile.setExecutable(true, true)) throw new IOException();
	}

	protected InputStream getExecutableInputStream(String basename) {
		String ext = getExecutableExtension();
		return requireNonNull(getResourceInputStream(architecture + "/" + basename, ext));
	}

	protected String getExecutableExtension() {
		return "";
	}

	private static void append(StringBuilder strb, String name, Object value) {
		strb.append(name);
		strb.append(" ");
		strb.append(value);
		strb.append("\n");
	}

	private InputStream getConfigInputStream() {
		File dataDirectory = new File(torDirectory, ".tor");
		StringBuilder strb = new StringBuilder();
		append(strb, "ControlPort", torControlPort);
		append(strb, "CookieAuthentication", 1);
		append(strb, "DataDirectory", dataDirectory.getAbsolutePath());
		append(strb, "DisableNetwork", 1);
		append(strb, "SafeSocks", 1);
		append(strb, "SocksPort", torSocksPort);
		strb.append("GeoIPFile\n");
		strb.append("GeoIPv6File\n");
		append(strb, "ConnectionPadding", 0);
		String lyrebirdPath = getLyrebirdExecutableFile().getAbsolutePath();
		append(strb, "ClientTransportPlugin obfs4 exec", lyrebirdPath);
		append(strb, "ClientTransportPlugin meek_lite exec", lyrebirdPath);
		append(strb, "ClientTransportPlugin snowflake exec", lyrebirdPath);
		return new ByteArrayInputStream(strb.toString().getBytes(UTF_8));
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
			tryToClose(in, LOG, WARNING);
		}
	}

	protected void waitForTorToStart(Process torProcess) throws InterruptedException, IOException {
		// Wait for the control port to be opened, then continue to read Tor's
		// stdout and stderr in a background thread until it exits.
		BlockingQueue<Boolean> success = new ArrayBlockingQueue<>(1);
		ioExecutor.execute(() -> {
			boolean started = false;
			// Read the process's stdout (and redirected stderr)
			Scanner stdout = new Scanner(torProcess.getInputStream());
			// Log the first line of stdout (contains Tor and library versions)
			if (stdout.hasNextLine()) LOG.info(stdout.nextLine());
			// Startup has succeeded when the control port is open
			while (stdout.hasNextLine()) {
				String line = stdout.nextLine();
				if (!started && line.contains("Opened Control listener")) {
					success.add(true);
					started = true;
				}
			}
			stdout.close();
			// If the control port wasn't opened, startup has failed
			if (!started) success.add(false);
			// Wait for the process to exit
			try {
				int exit = torProcess.waitFor();
				if (LOG.isLoggable(INFO)) LOG.info("Tor exited with value " + exit);
			} catch (InterruptedException e1) {
				LOG.warning("Interrupted while waiting for Tor to exit");
				Thread.currentThread().interrupt();
			}
		});
		// Wait for the startup result
		if (!success.take()) throw new IOException();
	}

	@Override
	public HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privKey) throws IOException {
		Map<Integer, String> portLines = singletonMap(remotePort, "127.0.0.1:" + localPort);
		// Use the control connection to set up the hidden service
		Map<String, String> response;
		if (privKey == null) {
			response = getControlConnection().addOnion("NEW:ED25519-V3", portLines, null);
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
		if (!state.enableNetwork(enable)) return; // Unchanged
		getControlConnection().setConf("DisableNetwork", enable ? "0" : "1");
	}

	@Override
	public void enableBridges(List<String> bridges) throws IOException {
		if (!state.setBridges(bridges)) return; // Unchanged
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
		if (!state.setBridges(emptyList())) return; // Unchanged
		getControlConnection().setConf("UseBridges", "0");
	}

	@Override
	public void stop() throws IOException, InterruptedException {
		if (!state.setStopping()) return; // Not in the appropriate state
		try {
			if (controlConnection != null) {
				controlConnection.shutdownTor("TERM");
			}
		} finally {
			controlConnection = null;
			tryToClose(controlSocket, LOG, WARNING);
			controlSocket = null;
			try {
				if (torProcess != null) {
					torProcess.waitFor();
				}
			} finally {
				torProcess = null;
				state.setStopped();
			}
		}
	}

	@Override
	public void circuitStatus(String status, String id, String path) {
		// In case of races between receiving CIRCUIT_ESTABLISHED and setting
		// DisableNetwork, set our circuitBuilt flag if not already set
		if (status.equals("BUILT") && state.setCircuitBuilt(true)) {
			LOG.info("Circuit built");
		}
	}

	@Override
	public void streamStatus(String status, String id, String target) {
	}

	@Override
	public void orConnStatus(String status, String orName) {
		if (LOG.isLoggable(INFO)) LOG.info("OR connection " + status);

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
		if (LOG.isLoggable(INFO)) LOG.info(severity + " " + msg);
	}

	@Override
	public void unrecognized(String type, String msg) {
		if (type.equals("STATUS_CLIENT")) {
			handleClientStatus(removeSeverity(msg));
		} else if (type.equals("STATUS_GENERAL")) {
			handleGeneralStatus(removeSeverity(msg));
		} else if (type.equals("HS_DESC") && msg.startsWith("UPLOADED")) {
			String[] parts = msg.split(" ");
			if (parts.length < 2) {
				LOG.warning("Failed to parse HS_DESC UPLOADED event");
			} else if (LOG.isLoggable(INFO)) {
				String onion = parts[1];
				LOG.info("V3 descriptor uploaded for " + scrubOnion(onion));
				state.onHsDescriptorUploaded(onion);
			}
		}
	}

	private String removeSeverity(String msg) {
		return msg.replaceFirst("[^ ]+ ", "");
	}

	private void handleClientStatus(String msg) {
		if (msg.startsWith("BOOTSTRAP PROGRESS=")) {
			int percentage = parseBootstrapPercentage(msg);
			if (percentage == 100) LOG.info("Bootstrapped");
			state.setBootstrapPercentage(percentage);
		} else if (msg.startsWith("CIRCUIT_ESTABLISHED")) {
			if (state.setCircuitBuilt(true)) LOG.info("Circuit built");
		} else if (msg.startsWith("CIRCUIT_NOT_ESTABLISHED")) {
			if (state.setCircuitBuilt(false)) {
				LOG.info("Circuit not built");
				// TODO: Disable and re-enable network to prompt Tor to rebuild
				//  its guard/bridge connections? This will also close any
				//  established circuits, which might still be functioning
			}
		}
	}

	private int parseBootstrapPercentage(String s) {
		Matcher matcher = BOOTSTRAP_PERCENTAGE.matcher(s);
		if (matcher.matches()) {
			try {
				return Integer.parseInt(matcher.group(1));
			} catch (NumberFormatException e) {
				// Fall through
			}
		}
		if (LOG.isLoggable(WARNING)) {
			LOG.warning("Failed to parse bootstrap percentage: " + s);
		}
		return 0;
	}

	private void handleGeneralStatus(String msg) {
		if (msg.startsWith("CLOCK_JUMPED")) {
			Long time = parseLongArgument(msg, "TIME");
			if (time != null && LOG.isLoggable(WARNING)) {
				LOG.warning("Clock jumped " + time + " seconds");
			}
		} else if (msg.startsWith("CLOCK_SKEW")) {
			Long skew = parseLongArgument(msg, "SKEW");
			if (skew != null) {
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Clock is skewed by " + skew + " seconds");
				}
				state.onClockSkewDetected(skew);
			}
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
					break;
				}
			}
		}
		if (LOG.isLoggable(WARNING)) {
			LOG.warning("Failed to parse " + argName + " from '" + msg + "'");
		}
		return null;
	}

	@Override
	public void controlConnectionClosed() {
		if (state.isTorRunning()) {
			// TODO: Restart the Tor process
			LOG.warning("Control connection closed");
		}
	}

	@Override
	public void enableConnectionPadding(boolean enable) throws IOException {
		if (!state.enableConnectionPadding(enable)) return; // Unchanged
		getControlConnection().setConf("ConnectionPadding", enable ? "1" : "0");
	}

	@Override
	public void enableIpv6(boolean enable) throws IOException {
		if (!state.enableIpv6(enable)) return; // Unchanged
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
		TorControlConnection controlConnection = this.controlConnection;
		if (controlConnection == null) {
			throw new IOException("Control connection not opened");
		}
		return controlConnection;
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
				paddingEnabled = false,
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
					// Notify the observer on the event executor
					eventExecutor.execute(() -> observer.onState(newState));
				}
			}
		}

		/**
		 * If the current process state is {@link ProcessState#NOT_STARTED NOT_STARTED} or
		 * {@link ProcessState#STOPPED STOPPED}, sets the process state to
		 * {@link ProcessState#STARTING STARTING} and returns true. Otherwise returns false.
		 */
		private synchronized boolean setStarting() {
			// It's appropriate to call start() if the wrapper has never been started,
			// or has been started and then stopped
			if (processState != ProcessState.NOT_STARTED && processState != ProcessState.STOPPED) {
				return false;
			}
			processState = ProcessState.STARTING;
			updateState();
			return true;
		}

		private synchronized void setStarted() {
			// We should always be in the STARTING state when this is called
			if (processState != ProcessState.STARTING) throw new IllegalStateException();
			processState = ProcessState.STARTED;
			updateState();
		}

		private synchronized void setStartupFailed() {
			// We should always be in the STARTING state when this is called
			if (processState != ProcessState.STARTING) throw new IllegalStateException();
			processState = ProcessState.STOPPED;
			// Reset all state related to the failed attempt
			networkInitialised = false;
			networkEnabled = false;
			paddingEnabled = false;
			ipv6Enabled = false;
			circuitBuilt = false;
			bootstrapPercentage = 0;
			bridges = emptyList();
			orConnectionsConnected = 0;
			updateState();
		}

		/**
		 * Returns true if the current process state is {@link ProcessState#STARTED STARTED}.
		 */
		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private synchronized boolean isTorRunning() {
			return processState == ProcessState.STARTED;
		}

		/**
		 * If the current process state is {@link ProcessState#STARTING STARTING} or
		 * {@link ProcessState#STARTED STARTED}, changes the process state to
		 * {@link ProcessState#STOPPING STOPPING} and returns true. Otherwise returns false.
		 */
		private synchronized boolean setStopping() {
			// It's appropriate to call stop() if start() has returned without throwing
			// an exception
			if (processState != ProcessState.STARTED) return false;
			processState = ProcessState.STOPPING;
			updateState();
			return true;
		}

		private synchronized void setStopped() {
			// We should always be in the STOPPING state when this is called
			if (processState != ProcessState.STOPPING) throw new IllegalStateException();
			processState = ProcessState.STOPPED;
			// Reset all state related to the process that has stopped
			networkInitialised = false;
			networkEnabled = false;
			paddingEnabled = false;
			ipv6Enabled = false;
			circuitBuilt = false;
			bootstrapPercentage = 0;
			bridges = emptyList();
			orConnectionsConnected = 0;
			updateState();
		}

		private synchronized void setBootstrapPercentage(int percentage) {
			if (percentage == bootstrapPercentage) return;
			bootstrapPercentage = percentage;
			if (observer != null) {
				// Notify the observer on the event executor
				eventExecutor.execute(() -> observer.onBootstrapPercentage(percentage));
			}
			updateState();
		}

		/**
		 * Sets the `circuitBuilt` flag and returns true if the flag has
		 * changed.
		 */
		private synchronized boolean setCircuitBuilt(boolean built) {
			if (built == circuitBuilt) return false; // Unchanged
			circuitBuilt = built;
			updateState();
			return true; // Changed
		}

		/**
		 * Sets the `networkEnabled` flag and returns true if the flag has
		 * changed.
		 */
		private synchronized boolean enableNetwork(boolean enable) {
			boolean wasInitialised = networkInitialised;
			boolean wasEnabled = networkEnabled;
			networkInitialised = true;
			networkEnabled = enable;
			if (!enable) circuitBuilt = false;
			if (!wasInitialised || enable != wasEnabled) {
				updateState();
			}
			return enable != wasEnabled;
		}

		/**
		 * Sets the `paddingEnabled` flag and returns true if the flag has
		 * changed. Doesn't affect getState().
		 */
		private synchronized boolean enableConnectionPadding(boolean enable) {
			if (enable == paddingEnabled) return false; // Unchanged
			paddingEnabled = enable;
			return true; // Changed
		}

		/**
		 * Sets the `ipv6Enabled` flag and returns true if the flag has
		 * changed. Doesn't affect getState().
		 */
		private synchronized boolean enableIpv6(boolean enable) {
			if (enable == ipv6Enabled) return false; // Unchanged
			ipv6Enabled = enable;
			return true; // Changed
		}

		/**
		 * Sets the list of bridges being used and returns true if the
		 * list has changed. The list is empty if bridges are disabled.
		 * Doesn't affect getState().
		 */
		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private synchronized boolean setBridges(List<String> bridges) {
			if (this.bridges.equals(bridges)) return false; // Unchanged
			this.bridges = bridges;
			return true; // Changed
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
			logOrConnections();
			if (oldConnected == 0) updateState();
		}

		private synchronized void onOrConnectionClosed() {
			int oldConnected = orConnectionsConnected;
			orConnectionsConnected--;
			if (orConnectionsConnected < 0) {
				LOG.warning("Count was zero before connection closed");
				orConnectionsConnected = 0;
			}
			logOrConnections();
			if (orConnectionsConnected == 0 && oldConnected != 0) {
				updateState();
			}
		}

		@GuardedBy("this")
		private void logOrConnections() {
			if (LOG.isLoggable(INFO)) {
				LOG.info(orConnectionsConnected + " OR connections connected");
			}
		}

		private synchronized void onHsDescriptorUploaded(String onion) {
			if (observer != null) {
				// Notify the observer on the event executor
				eventExecutor.execute(() -> observer.onHsDescriptorUpload(onion));
			}
		}

		private synchronized void onClockSkewDetected(long skewSeconds) {
			if (observer != null) {
				// Notify the observer on the event executor
				eventExecutor.execute(() -> observer.onClockSkewDetected(skewSeconds));
			}
		}
	}
}
