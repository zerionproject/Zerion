package org.zerionproject.core.contact.request;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.plugin.tor.InboxOnionAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeTorNetwork {

	final Map<String, String> onionByBlob = new ConcurrentHashMap<>();
	final Map<String, Listener> listenersByTarget = new ConcurrentHashMap<>();
	final Map<String, Listener> listenersByOnion = new ConcurrentHashMap<>();
	final Map<String, byte[]> clientKeys = new ConcurrentHashMap<>();
	final List<String> removed = new ArrayList<>();
	final List<String> publishedOnions = new ArrayList<>();
	final AtomicInteger connections = new AtomicInteger();
	final AtomicInteger clientKeysAdded = new AtomicInteger();
	final AtomicInteger clientKeysForgotten = new AtomicInteger();
	volatile Runnable beforeConnect = null;
	volatile Runnable afterReading = null;
	volatile int afterReadingBytes = 0;

	public void learn(ContactLinkStore links,
			Iterable<org.zerionproject.core.api.contact.request.ContactLink>
					all) {
		for (org.zerionproject.core.api.contact.request.ContactLink l : all) {
			if (l.getInboxKey() == null) continue;
			onionByBlob.put(links.inboxKeyBlob(l), links.inboxOnion(l));
		}
	}

	static final class Listener implements OnionTargetListener {

		final ServerSocket server;
		final String target;

		Listener(ServerSocket server) {
			this.server = server;
			target = "unix:/fake/" + server.getLocalPort();
		}

		@Override
		public String getTorTarget() {
			return target;
		}

		@Override
		public Socket accept() throws IOException {
			return server.accept();
		}

		@Override
		public boolean isClosed() {
			return server.isClosed();
		}

		@Override
		public void close() throws IOException {
			server.close();
		}
	}

	public final class Adapter implements InboxOnionAdapter {

		public volatile boolean connected = true;
		public volatile boolean powProtected = false;
		public volatile boolean failAfterPublish = false;

		@Override
		public boolean isConnected() {
			return connected;
		}

		@Override
		public OnionTargetListener openOnionTarget() throws IOException {
			Listener l = new Listener(new ServerSocket(0, 50,
					InetAddress.getByName("127.0.0.1")));
			listenersByTarget.put(l.target, l);
			return l;
		}

		@Override
		public String publishInboxOnion(String target, String privateKeyBlob,
				byte[] clientAuthPublicKey) throws IOException {
			if (!connected) throw new IOException();
			String onion = onionByBlob.get(privateKeyBlob);
			if (onion == null) throw new IOException("unknown key");
			Listener l = listenersByTarget.get(target);
			if (l == null) throw new IOException("unknown target");
			listenersByOnion.put(onion, l);
			clientKeys.put(onion, clientAuthPublicKey.clone());
			synchronized (publishedOnions) {
				publishedOnions.add(onion);
			}
			return onion;
		}

		@Override
		public boolean isInboxPowProtected(String onion) {
			if (failAfterPublish) throw new IllegalStateException("after publish");
			return powProtected;
		}

		@Override
		public void removeInboxOnion(String onion) {
			listenersByOnion.remove(onion);
			synchronized (removed) {
				removed.add(onion);
			}
		}

		@Override
		public Socket connectToInbox(String onion, byte[] clientPrivateKey)
				throws IOException {
			if (!connected) throw new IOException();
			Runnable hook = beforeConnect;
			beforeConnect = null;
			if (hook != null) hook.run();
			clientKeysAdded.incrementAndGet();
			Listener l = listenersByOnion.get(onion);
			byte[] expected = clientKeys.get(onion);
			if (l == null || l.isClosed() || expected == null) {
				throw new IOException("unreachable");
			}
			byte[] pub = new X25519PrivateKeyParameters(clientPrivateKey, 0)
					.generatePublicKey().getEncoded();
			if (!Arrays.equals(pub, expected)) {
				throw new IOException("descriptor not decryptable");
			}
			connections.incrementAndGet();
			Runnable readHook = afterReading;
			afterReading = null;
			if (readHook == null) {
				return new Socket(InetAddress.getByName("127.0.0.1"),
						l.server.getLocalPort());
			}
			return new HookedSocket(l.server.getLocalPort(), afterReadingBytes,
					readHook);
		}

		@Override
		public void forgetInboxClientKey(String onion) {
			clientKeysForgotten.incrementAndGet();
		}
	}

	static final class HookedSocket extends Socket {

		private final int threshold;
		private final Runnable hook;
		private InputStream in = null;

		HookedSocket(int port, int threshold, Runnable hook)
				throws IOException {
			super(InetAddress.getByName("127.0.0.1"), port);
			this.threshold = threshold;
			this.hook = hook;
		}

		@Override
		public synchronized InputStream getInputStream() throws IOException {
			if (in == null) {
				in = new FilterInputStream(super.getInputStream()) {
					private int count = 0;
					private boolean fired = false;

					private void counted(int n) {
						if (n <= 0) return;
						count += n;
						if (!fired && count >= threshold) {
							fired = true;
							hook.run();
						}
					}

					@Override
					public int read() throws IOException {
						int b = super.read();
						if (b >= 0) counted(1);
						return b;
					}

					@Override
					public int read(byte[] b, int off, int len)
							throws IOException {
						int n = super.read(b, off, len);
						counted(n);
						return n;
					}
				};
			}
			return in;
		}
	}

	public List<String> removedOnions() {
		synchronized (removed) {
			return new ArrayList<>(removed);
		}
	}

	public boolean isReachable(String onion) {
		Listener l = listenersByOnion.get(onion);
		return l != null && !l.isClosed();
	}
}
