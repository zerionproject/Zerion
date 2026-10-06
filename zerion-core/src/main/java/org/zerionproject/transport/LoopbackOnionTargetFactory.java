package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

@NotNullByDefault
public class LoopbackOnionTargetFactory implements OnionTargetFactory {

	@Override
	public OnionTargetListener open() throws IOException {
		ServerSocket ss = new ServerSocket();
		try {
			ss.bind(new InetSocketAddress("127.0.0.1", 0));
		} catch (IOException e) {
			ss.close();
			throw e;
		}
		return new Listener(ss);
	}

	private static final class Listener implements OnionTargetListener {

		private final ServerSocket ss;
		private final String target;

		private Listener(ServerSocket ss) {
			this.ss = ss;
			this.target = OnionTargets.loopback(ss.getLocalPort());
		}

		@Override
		public String getTorTarget() {
			return target;
		}

		@Override
		public Socket accept() throws IOException {
			return ss.accept();
		}

		@Override
		public boolean isClosed() {
			return ss.isClosed();
		}

		@Override
		public void close() throws IOException {
			ss.close();
		}
	}
}
