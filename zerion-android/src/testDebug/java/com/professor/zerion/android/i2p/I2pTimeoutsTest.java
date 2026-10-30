package com.professor.zerion.android.i2p;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base64;
import net.i2p.data.Destination;

import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pRouter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class I2pTimeoutsTest {

	private final ZtpConnectionHandler handler =
			mock(ZtpConnectionHandler.class);
	private final I2PSocket socket = mock(I2PSocket.class);
	private final I2pStreamingTransport transport = new I2pStreamingTransport(
			mock(I2pRouter.class), Runnable::run, handler);

	@Test
	public void aDialledSocketHasAReadTimeoutBeforeTheSessionRuns()
			throws Exception {
		I2PSocketManager mgr = mock(I2PSocketManager.class);
		I2PSocketOptions options = mock(I2PSocketOptions.class);
		when(mgr.buildOptions()).thenReturn(options);
		when(mgr.connect(any(Destination.class))).thenReturn(socket);
		when(mgr.connect(any(Destination.class), any(I2PSocketOptions.class)))
				.thenReturn(socket);
		stubStreams();
		setManager(mgr);

		transport.dial(1, destination(), false);

		verify(options).setConnectTimeout(60_000L);
		verify(options).setReadTimeout(60_000L);
		InOrder order = inOrder(socket, handler);
		order.verify(socket).setReadTimeout(longThat(t -> t > 0));
		order.verify(handler).handleOutgoingSession(any(TransportId.class),
				anyInt(), any(InputStream.class), any(OutputStream.class));
	}

	@Test
	public void anAcceptedSocketHasAReadTimeoutBeforeTheSessionRuns()
			throws Exception {
		stubStreams();
		Method accepted = I2pStreamingTransport.class.getDeclaredMethod(
				"handleAccepted", I2PSocket.class);
		accepted.setAccessible(true);

		accepted.invoke(transport, socket);

		InOrder order = inOrder(socket, handler);
		order.verify(socket).setReadTimeout(longThat(t -> t > 0));
		order.verify(handler).handleIncoming(any(TransportId.class),
				any(InputStream.class), any(OutputStream.class));
	}

	@Test
	public void theRouterNeverQueriesNtpOutsideTor() throws Exception {
		BundledI2pRouter router = new BundledI2pRouter(
				RuntimeEnvironment.getApplication(), 9050, "", () -> false,
				() -> true);
		Method props = BundledI2pRouter.class.getDeclaredMethod(
				"routerProperties", boolean.class);
		props.setAccessible(true);
		for (boolean direct : new boolean[] {false, true}) {
			Properties p = (Properties) props.invoke(router, direct);
			assertEquals("SNTP must be off, direct reseed " + direct, "true",
					p.getProperty("time.disabled"));
		}
	}

	private void stubStreams() throws Exception {
		when(socket.getInputStream())
				.thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
	}

	private void setManager(I2PSocketManager mgr) throws Exception {
		Field f = I2pStreamingTransport.class.getDeclaredField("manager");
		f.setAccessible(true);
		f.set(transport, mgr);
	}

	private static String destination() {
		byte[] raw = new byte[256 + 128 + 3];
		new SecureRandom().nextBytes(raw);
		raw[384] = 0;
		raw[385] = 0;
		raw[386] = 0;
		return Base64.encode(raw);
	}
}
