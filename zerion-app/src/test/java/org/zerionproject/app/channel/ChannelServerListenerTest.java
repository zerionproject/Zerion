package org.zerionproject.app.channel;

import org.junit.Test;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.plugin.OnionTargetListener;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelServerListenerTest {

	private static final String UNIX_TARGET = "unix:/test/zo/channel1";

	private static final class ClosedListener implements OnionTargetListener {

		final AtomicBoolean closed = new AtomicBoolean();
		private final CountDownLatch closing = new CountDownLatch(1);

		@Override
		public String getTorTarget() {
			return UNIX_TARGET;
		}

		@Override
		public Socket accept() throws IOException {
			try {
				closing.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			throw new SocketException("closed");
		}

		@Override
		public boolean isClosed() {
			return closed.get();
		}

		@Override
		public void close() {
			closed.set(true);
			closing.countDown();
		}
	}

	private static TorChannelTransport transport(OnionPublisher p) {
		return new TorChannelTransport(p, SocketFactory.getDefault(),
				r -> new Thread(r).start());
	}

	@Test(timeout = 20_000)
	public void theServerListensWhereThePublishersServiceForwards()
			throws Exception {
		ClosedListener listener = new ClosedListener();
		List<String> targets = Collections.synchronizedList(new ArrayList<>());
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort,
					@Nullable String privateKey) {
				throw new AssertionError("a loopback port was published");
			}

			@Override
			public OnionTargetListener openTarget() {
				return listener;
			}

			@Override
			public OnionHandle publish(String target,
					@Nullable String privateKey) {
				targets.add(target);
				return new OnionHandle("channelonion", "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		ChannelTransport.ChannelServer server = transport(publisher)
				.bindServer(new byte[32], null, request -> new byte[0]);
		assertEquals(Collections.singletonList(UNIX_TARGET), targets);
		assertEquals("channelonion", server.getOnionAddress());
		server.close();
		assertTrue(listener.closed.get());
	}

	@Test(timeout = 20_000)
	public void aListenerWhoseServiceCannotBePublishedIsClosed()
			throws Exception {
		AtomicReference<Integer> port = new AtomicReference<>();
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort,
					@Nullable String privateKey) throws IOException {
				port.set(localPort);
				throw new IOException("Tor plugin not yet started");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		try {
			transport(publisher).bindServer(new byte[32], null,
					request -> new byte[0]);
			fail();
		} catch (IOException expected) {
		}
		Integer p = port.get();
		assertTrue(p != null && p > 0);
		try (Socket s = new Socket("127.0.0.1", p)) {
			fail("the listener of an unpublished service is still open");
		} catch (IOException expected) {
		}
	}
}
