package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.TorControlPort;
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

/**
 * Publishes onion services with Tor v3 client authorization and registers
 * client keys, over the transport's own authenticated control connection.
 * The onion wrapper's publish call cannot carry client keys, so the
 * authorized service goes through here; it is created detached so it
 * outlives the control connection that created it.
 */
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

	private final File torDirectory;
	private final TorControl.ConnectionFactory connectionFactory;

	@Inject
	public TorOnionServiceControl(@TorDirectory File torDirectory,
			@TorControlPort int controlPort) {
		this(torDirectory, () -> new TorControl.SocketConnection(controlPort));
	}

	TorOnionServiceControl(File torDirectory,
			TorControl.ConnectionFactory connectionFactory) {
		this.torDirectory = torDirectory;
		this.connectionFactory = connectionFactory;
	}

	/**
	 * Publishes a service whose descriptor is encrypted for the given client
	 * public keys. With a null private key Tor generates a new one and it is
	 * returned so the caller can persist it.
	 */
	@Override
	public Published publish(@Nullable String privateKey, int localPort,
			int remotePort, Collection<byte[]> clientPublicKeys)
			throws IOException {
		if (clientPublicKeys.isEmpty()) {
			throw new IllegalArgumentException("no client keys");
		}
		StringBuilder cmd = new StringBuilder("ADD_ONION ");
		cmd.append(privateKey == null ? "NEW:ED25519-V3" : privateKey);
		cmd.append(" Flags=Detach,V3Auth");
		for (byte[] key : clientPublicKeys) {
			cmd.append(" ClientAuthV3=").append(encodeClientPublicKey(key));
		}
		cmd.append(" Port=").append(remotePort).append(",127.0.0.1:")
				.append(localPort);
		List<String> reply = command(cmd.toString(), "ADD_ONION");
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

	/**
	 * Hands Tor the private half of a client key for a peer's service, so
	 * that dials to that onion can decrypt its descriptor. The key is kept
	 * in Tor's memory only, for the lifetime of the Tor process.
	 */
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

	/**
	 * Every reply Tor documents for ONION_CLIENT_AUTH_ADD, classified
	 * exhaustively: 250 added, 251 added and the previous credential
	 * replaced, 252 added and a cached descriptor decrypted are success;
	 * 451 is the client capacity, reported as its own exception; 512, 551,
	 * 552 and anything else, positive or negative, fail.
	 */
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

	/**
	 * Tor answers 250 when the credential was removed and 251 when none
	 * was registered; both leave Tor without a credential for the address.
	 */
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

	/** Tor's spelling of a client public key: base32 without padding. */
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
