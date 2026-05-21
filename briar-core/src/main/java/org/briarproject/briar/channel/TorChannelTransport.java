package org.briarproject.briar.channel;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.net.SocketFactory;

@NotNullByDefault
public class TorChannelTransport implements ChannelTransport {

	private static final int CONNECT_TIMEOUT_MS = 60_000;
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int REMOTE_PORT = 80;
	private static final int MAX_REQUEST_BYTES = 256 * 1024;
	private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

	private final OnionPublisher onionPublisher;
	private final SocketFactory torSocketFactory;
	private final Executor ioExecutor;
	private final ConcurrentHashMap<String, ServerSocket>
			boundSockets = new ConcurrentHashMap<>();

	@Inject
	public TorChannelTransport(OnionPublisher onionPublisher,
			SocketFactory torSocketFactory,
			@IoExecutor Executor ioExecutor) {
		this.onionPublisher = onionPublisher;
		this.torSocketFactory = torSocketFactory;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			@javax.annotation.Nullable String onionPrivateKey,
			ChannelRequestHandler handler) throws IOException {
		ServerSocket ss = new ServerSocket();
		ss.bind(new InetSocketAddress("127.0.0.1", 0));
		int localPort = ss.getLocalPort();
		OnionPublisher.OnionHandle handle =
				onionPublisher.publish(localPort, onionPrivateKey);
		String onion = handle.getOnion();
		String returnedPrivKey = handle.getPrivateKey();
		boundSockets.put(onion, ss);
		ioExecutor.execute(() -> acceptLoop(ss, handler));
		return new ChannelServer() {
			@Override
			public String getOnionAddress() {
				return onion;
			}

			@javax.annotation.Nullable
			@Override
			public String getOnionPrivateKey() {
				return returnedPrivKey;
			}

			@Override
			public void close() {
				try {
					ss.close();
				} catch (IOException ignored) {
				}
				boundSockets.remove(onion);
				try {
					onionPublisher.unpublish(onion);
				} catch (IOException ignored) {
				}
			}
		};
	}

	@Override
	public byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException {
		if (requestBytes.length > MAX_REQUEST_BYTES) {
			throw new IOException("Request too large");
		}
		Socket s = torSocketFactory.createSocket();
		try {
			s.connect(new InetSocketAddress(stripDotOnion(onion)
					+ ".onion", REMOTE_PORT), CONNECT_TIMEOUT_MS);
			s.setSoTimeout(READ_TIMEOUT_MS);
			DataOutputStream out = new DataOutputStream(
					s.getOutputStream());
			out.writeInt(requestBytes.length);
			out.write(requestBytes);
			out.flush();
			DataInputStream in = new DataInputStream(
					s.getInputStream());
			int len = in.readInt();
			if (len < 0 || len > MAX_RESPONSE_BYTES) {
				throw new IOException(
						"Invalid response length: " + len);
			}
			byte[] body = new byte[len];
			in.readFully(body);
			return body;
		} finally {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void acceptLoop(ServerSocket ss,
			ChannelRequestHandler handler) {
		while (!ss.isClosed()) {
			Socket client;
			try {
				client = ss.accept();
			} catch (IOException e) {
				return;
			}
			ioExecutor.execute(() -> handleClient(client, handler));
		}
	}

	private void handleClient(Socket client,
			ChannelRequestHandler handler) {
		try {
			client.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(
					client.getInputStream());
			int len = in.readInt();
			if (len < 0 || len > MAX_REQUEST_BYTES) return;
			byte[] body = new byte[len];
			in.readFully(body);
			byte[] response = handler.handle(body);
			if (response == null) response = new byte[0];
			DataOutputStream out = new DataOutputStream(
					client.getOutputStream());
			out.writeInt(response.length);
			out.write(response);
			out.flush();
		} catch (IOException ignored) {
		} finally {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static String stripDotOnion(String onion) {
		if (onion.endsWith(".onion")) {
			return onion.substring(0, onion.length() - 6);
		}
		return onion;
	}
}
