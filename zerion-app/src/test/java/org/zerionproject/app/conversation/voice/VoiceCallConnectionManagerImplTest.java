package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VoiceCallConnectionManagerImplTest {

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final VoiceCallCrypto crypto = context.mock(VoiceCallCrypto.class);
	private final DuplexTransportConnection late =
			context.mock(DuplexTransportConnection.class, "late");
	private final DuplexTransportConnection live =
			context.mock(DuplexTransportConnection.class, "live");
	private final TransportConnectionReader lateReader =
			context.mock(TransportConnectionReader.class, "lateReader");
	private final TransportConnectionWriter lateWriter =
			context.mock(TransportConnectionWriter.class, "lateWriter");
	private final SecretKey key = new SecretKey(new byte[32]);

	@Test
	public void aLateConnectionFromAnAbandonedAttemptIsClosed()
			throws Exception {
		CountDownLatch closed = new CountDownLatch(2);
		CountDownLatch secondDial = new CountDownLatch(1);
		AtomicInteger dials = new AtomicInteger();
		DuplexPlugin plugin = dialler(dials, secondDial);
		context.checking(new Expectations() {{
			allowing(pluginManager).getPlugin(TorConstants.ID);
			will(returnValue(plugin));
			allowing(late).getReader();
			will(returnValue(lateReader));
			allowing(late).getWriter();
			will(returnValue(lateWriter));
			oneOf(lateReader).dispose(false, true);
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					closed.countDown();
					return null;
				}

				@Override
				public void describeTo(
						org.hamcrest.Description description) {
					description.appendText("counts the reader close");
				}
			});
			oneOf(lateWriter).dispose(false);
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					closed.countDown();
					return null;
				}

				@Override
				public void describeTo(
						org.hamcrest.Description description) {
					description.appendText("counts the writer close");
				}
			});
			never(live).getReader();
			never(live).getWriter();
		}});
		VoiceCallConnectionManagerImpl manager =
				new VoiceCallConnectionManagerImpl(pluginManager, crypto,
						500, new long[] {0, 50});
		try {
			DuplexTransportConnection conn = manager.connectToRemote("call",
					"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxyz234567.onion",
					key, true);
			assertSame(live, conn);
			assertTrue("the late connection was not closed",
					closed.await(10, TimeUnit.SECONDS));
			assertEquals(2, dials.get());
			context.assertIsSatisfied();
		} finally {
			manager.shutdown();
		}
	}

	private DuplexPlugin dialler(AtomicInteger dials,
			CountDownLatch secondDial) {
		return (DuplexPlugin) Proxy.newProxyInstance(
				DuplexPlugin.class.getClassLoader(),
				new Class<?>[] {DuplexPlugin.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "createConnection":
							if (dials.incrementAndGet() == 1) {
								secondDial.await(10, TimeUnit.SECONDS);
								return late;
							}
							secondDial.countDown();
							return live;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						case "toString":
							return "dialler";
						default:
							throw new UnsupportedOperationException(
									method.getName());
					}
				});
	}
}
