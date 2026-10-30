package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactRequestConstants;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.api.plugin.TorDirectory;
import org.zerionproject.core.util.Base32;
import org.zerionproject.core.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
public class TorOnionServiceControl implements
		org.zerionproject.core.plugin.tor.auth.OnionServiceControl {

	static final int KEY_BYTES = 32;
	private static final Pattern ONION =
			Pattern.compile("^[a-z2-7]{56}$");
	private static final Pattern SERVICE_ID =
			Pattern.compile("^250-ServiceID=([a-z2-7]{56})$");
	private static final Pattern PRIVATE_KEY =
			Pattern.compile("^250-PrivateKey=(ED25519-V3:[A-Za-z0-9+/=]+)$");
	private static final Pattern VERSION = Pattern.compile(
			"^250-version=(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
	static final int[] POW_MIN_VERSION = {0, 4, 9, 2};
	static final String INBOX_FLAGS = ",MaxStreamsCloseCircuit";
	static final String INBOX_OPTIONS = " MaxStreams=1";
	static final String POW_OPTIONS = " PoWDefensesEnabled=1"
			+ " PoWQueueRate=" + ContactRequestConstants.INBOX_POW_QUEUE_RATE
			+ " PoWQueueBurst=" + ContactRequestConstants.INBOX_POW_QUEUE_BURST;

	private final File torDirectory;
	private final TorControl.ConnectionFactory connectionFactory;

	@Inject
	public TorOnionServiceControl(@TorDirectory File torDirectory,
			TorControlSocketFactory controlSockets) {
		this(torDirectory,
				() -> new TorControl.SocketConnection(controlSockets));
	}

	TorOnionServiceControl(File torDirectory,
			TorControl.ConnectionFactory connectionFactory) {
		this.torDirectory = torDirectory;
		this.connectionFactory = connectionFactory;
	}

	@Override
	public Published publish(@Nullable String privateKey, int localPort,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException {
		return publish(privateKey, OnionTargets.loopback(localPort),
				remotePort, clientPublicKeys);
	}

	@Override
	public Published publish(@Nullable String privateKey, String target,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException {
		return publish(privateKey, target, remotePort, clientPublicKeys, "",
				"");
	}

	public InboxPublished publishInbox(String privateKey, String target,
			int remotePort, byte[] clientPublicKey) throws IOException {
		List<byte[]> keys = java.util.Collections.singletonList(clientPublicKey);
		byte[] cookie = TorControl.readCookie(torDirectory);
		try (TorControl.Connection c = connectionFactory.open()) {
			TorControl.requireOk(c.send("AUTHENTICATE "
					+ StringUtils.toHexString(cookie)), "authentication");
			List<String> version;
			try {
				version = c.send("GETINFO version");
			} catch (IOException e) {
				version = null;
			}
			if (version != null) {
				boolean pow = supportsPow(version);
				List<String> reply = null;
				if (pow) {
					reply = c.send(addOnion(privateKey, target, remotePort, keys,
							INBOX_FLAGS, INBOX_OPTIONS + POW_OPTIONS));
					if (refusesPowKeywords(reply)) {
						pow = false;
						reply = null;
					}
				}
				if (reply == null) {
					reply = c.send(addOnion(privateKey, target, remotePort, keys,
							INBOX_FLAGS, INBOX_OPTIONS));
				}
				TorControl.requireOk(reply, "ADD_ONION");
				TorControl.quit(c);
				Published p = parseAddOnion(reply, privateKey);
				return new InboxPublished(p.onion, p.privateKey, pow);
			}
		} finally {
			Arrays.fill(cookie, (byte) 0);
		}
		Published p = publish(privateKey, target, remotePort, keys,
				INBOX_FLAGS, INBOX_OPTIONS);
		return new InboxPublished(p.onion, p.privateKey, false);
	}

	static boolean supportsPow(List<String> getInfoVersion) {
		if (getInfoVersion.isEmpty() || !getInfoVersion
				.get(getInfoVersion.size() - 1).startsWith("250")) {
			return false;
		}
		for (String line : getInfoVersion) {
			java.util.regex.Matcher m = VERSION.matcher(line);
			if (!m.find()) continue;
			for (int i = 0; i < POW_MIN_VERSION.length; i++) {
				int part;
				try {
					part = Integer.parseInt(m.group(i + 1));
				} catch (NumberFormatException e) {
					return false;
				}
				if (part != POW_MIN_VERSION[i]) return part > POW_MIN_VERSION[i];
			}
			return true;
		}
		return false;
	}

	static boolean refusesPowKeywords(List<String> reply) {
		if (reply.isEmpty()) return false;
		String last = reply.get(reply.size() - 1);
		if (!last.startsWith("552") && !last.startsWith("512")
				&& !last.startsWith("513")) {
			return false;
		}
		String text = last.toLowerCase(java.util.Locale.ROOT);
		return text.contains("unrecognized keyword") || text.contains("pow");
	}

	private Published publish(@Nullable String privateKey, String target,
			int remotePort, Collection<byte[]> clientPublicKeys,
			String extraFlags, String extraOptions) throws IOException {
		List<String> reply = command(addOnion(privateKey, target, remotePort,
				clientPublicKeys, extraFlags, extraOptions), "ADD_ONION");
		return parseAddOnion(reply, privateKey);
	}

	private static String addOnion(@Nullable String privateKey,
			String target, int remotePort, Collection<byte[]> clientPublicKeys,
			String extraFlags, String extraOptions) {
		if (clientPublicKeys.isEmpty()) {
			throw new IllegalArgumentException("no client keys");
		}
		if (!OnionTargets.isValid(target)) {
			throw new IllegalArgumentException("onion target");
		}
		StringBuilder cmd = new StringBuilder("ADD_ONION ");
		cmd.append(privateKey == null ? "NEW:ED25519-V3" : privateKey);
		cmd.append(" Flags=Detach,V3Auth").append(extraFlags);
		cmd.append(extraOptions);
		for (byte[] key : clientPublicKeys) {
			cmd.append(" ClientAuthV3=").append(encodeClientPublicKey(key));
		}
		cmd.append(" Port=").append(remotePort).append(',').append(target);
		return cmd.toString();
	}

	private static Published parseAddOnion(List<String> reply,
			@Nullable String privateKey) throws IOException {
		String onion = null, key = privateKey;
		for (String line : reply) {
			java.util.regex.Matcher m = SERVICE_ID.matcher(line);
			if (m.matches()) onion = m.group(1);
			m = PRIVATE_KEY.matcher(line);
			if (m.matches()) key = m.group(1);
		}
		if (onion == null || key == null) {
			throw new IOException("Tor control ADD_ONION reply incomplete");
		}
		return new Published(onion, key);
	}

	@Override
	public void remove(String onion) throws IOException {
		requireOnion(onion);
		command("DEL_ONION " + onion, "DEL_ONION");
	}

	@Override
	public void addClientKey(String onion, byte[] clientPrivateKey)
			throws IOException {
		requireOnion(onion);
		if (clientPrivateKey.length != KEY_BYTES) {
			throw new IllegalArgumentException("client key");
		}
		String b64 = Base64.getEncoder().encodeToString(clientPrivateKey);
		List<String> reply = command("ONION_CLIENT_AUTH_ADD " + onion
				+ " x25519:" + b64, "ONION_CLIENT_AUTH_ADD");
		classifyClientAuthReply(reply);
	}

	static void classifyClientAuthReply(List<String> reply)
			throws IOException {
		if (reply.isEmpty()) throw new IOException("empty reply");
		String last = reply.get(reply.size() - 1);
		if (last.length() < 3) throw new IOException("bad reply");
		String code = last.substring(0, 3);
		switch (code) {
			case "250":
			case "251":
			case "252":
				return;
			case "451":
				throw new CapacityException();
			case "512":
				throw new IOException("Tor rejected the client key or address");
			case "551":
				throw new IOException("Tor reported a client name conflict");
			case "552":
				throw new IOException("Tor does not support the key type");
			default:
				throw new IOException("Tor control ONION_CLIENT_AUTH_ADD "
						+ "unexpected reply " + code);
		}
	}

	@Override
	public void removeClientKey(String onion) throws IOException {
		requireOnion(onion);
		List<String> reply = command("ONION_CLIENT_AUTH_REMOVE " + onion,
				"ONION_CLIENT_AUTH_REMOVE");
		String last = reply.isEmpty() ? "" : reply.get(reply.size() - 1);
		if (!last.startsWith("250") && !last.startsWith("251")) {
			throw new IOException("Tor control ONION_CLIENT_AUTH_REMOVE "
					+ "failed");
		}
	}

	public static final class InboxPublished {

		public final String onion;
		public final String privateKey;
		public final boolean powProtected;

		InboxPublished(String onion, String privateKey, boolean powProtected) {
			this.onion = onion;
			this.privateKey = privateKey;
			this.powProtected = powProtected;
		}
	}

	static String encodeClientPublicKey(byte[] publicKey) {
		if (publicKey.length != KEY_BYTES) {
			throw new IllegalArgumentException("client key");
		}
		return Base32.encode(publicKey);
	}

	private static void requireOnion(String onion) {
		if (!ONION.matcher(onion).matches()) {
			throw new IllegalArgumentException("onion");
		}
	}

	private List<String> command(String cmd, String what) throws IOException {
		byte[] cookie = TorControl.readCookie(torDirectory);
		try (TorControl.Connection c = connectionFactory.open()) {
			TorControl.requireOk(c.send("AUTHENTICATE "
					+ StringUtils.toHexString(cookie)), "authentication");
			List<String> reply = c.send(cmd);
			if (!what.startsWith("ONION_CLIENT_AUTH_")) {
				TorControl.requireOk(reply, what);
			}
			TorControl.quit(c);
			return reply;
		} finally {
			Arrays.fill(cookie, (byte) 0);
		}
	}
}
