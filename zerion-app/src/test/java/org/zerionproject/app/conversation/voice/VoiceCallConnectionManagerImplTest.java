package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A dial that outlives its attempt must not hand a connection to the
 * callee later: the callee would adopt that orphan as the call and drop
 * the connection the caller actually streams on. The manager returns the
 * connection of the attempt that answered in time and closes the late one
 * as soon as it arrives.
 */
public class VoiceCallConnectionManagerImplTest {

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final VoiceCallCrypto crypto = context.mock(VoiceCallCrypto.class);
	private final DuplexPlugin plugin = context.mock(DuplexPlugin.class);
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
		AtomicInteger dials = new AtomicInteger();
		context.checking(new Expectations() {{
			allowing(pluginManager).getPlugin(TorConstants.ID);
			will(returnValue(plugin));
			allowing(plugin).createConnection(
					with(any(TransportProperties.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation)
						throws Throwable {
					if (dials.incrementAndGet() == 1) {
						Thread.sleep(800);
						return late;
					}
					return live;
				}

				@Override
				public void describeTo(
						org.hamcrest.Description description) {
					description.appendText(
							"blocks the first dial, answers the second");
				}
			});
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
}
