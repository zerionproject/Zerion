package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class UnixDomainTargets implements OnionTargetFactory {

	private final Path dir;
	private final AtomicInteger counter = new AtomicInteger();
	final List<Listener> opened =
			Collections.synchronizedList(new ArrayList<>());

	UnixDomainTargets() throws IOException {
		dir = Files.createTempDirectory("zo");
	}

	@Override
	public OnionTargetListener open() throws IOException {
		int n = counter.incrementAndGet();
		Path path = dir.resolve("t" + n);
		ServerSocketChannel ch =
				ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		ch.bind(UnixDomainSocketAddress.of(path));
		Listener l = new Listener(ch, path,
				OnionTargets.unixPath("/test/zo/t" + n));
		opened.add(l);
		return l;
	}

	Socket connect(int n) throws IOException {
		Listener l = opened.get(n - 1);
		SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
		ch.connect(UnixDomainSocketAddress.of(l.path));
		return new ChannelSocket(ch);
	}

	void deleteAll() {
		for (Listener l : new ArrayList<>(opened)) {
			try {
				l.close();
			} catch (IOException ignored) {
			}
		}
		try {
			Files.deleteIfExists(dir);
		} catch (IOException ignored) {
		}
	}

	static final class Listener implements OnionTargetListener {

		final ServerSocketChannel channel;
		final Path path;
		private final String target;

		private Listener(ServerSocketChannel channel, Path path,
				String target) {
			this.channel = channel;
			this.path = path;
			this.target = target;
		}

		@Override
		public String getTorTarget() {
			return target;
		}

		@Override
		public Socket accept() throws IOException {
			return new ChannelSocket(channel.accept());
		}

		@Override
		public boolean isClosed() {
			return !channel.isOpen();
		}

		@Override
		public void close() throws IOException {
			channel.close();
			Files.deleteIfExists(path);
		}
	}

	static final class ChannelSocket extends Socket {

		private final SocketChannel channel;
		private final InputStream in;
		private final OutputStream out;

		ChannelSocket(SocketChannel channel) {
			this.channel = channel;
			this.in = Channels.newInputStream(channel);
			this.out = Channels.newOutputStream(channel);
		}

		@Override
		public InputStream getInputStream() {
			return in;
		}

		@Override
		public OutputStream getOutputStream() {
			return out;
		}

		@Override
		public void setSoTimeout(int timeout) {
		}

		@Override
		public void setTcpNoDelay(boolean on) {
		}

		@Override
		public boolean isClosed() {
			return !channel.isOpen();
		}

		@Override
		public synchronized void close() throws IOException {
			channel.close();
		}
	}
}
