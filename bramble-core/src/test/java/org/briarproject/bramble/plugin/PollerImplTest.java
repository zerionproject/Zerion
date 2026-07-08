package org.briarproject.bramble.plugin;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.bramble.api.connection.ConnectionManager;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.event.ContactAddedEvent;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.plugin.event.ConnectionClosedEvent;
import org.briarproject.bramble.api.plugin.event.ConnectionOpenedEvent;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.plugin.simplex.SimplexPlugin;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.system.TaskScheduler;
import org.briarproject.bramble.crypto.NeitherSecureNorRandom;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.bramble.test.RunAction;
import org.jmock.Expectations;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executor;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.briarproject.bramble.test.CollectionMatcher.collectionOf;
import static org.briarproject.bramble.test.PairMatcher.pairOf;
import static org.briarproject.bramble.test.TestUtils.getContactId;
import static org.briarproject.bramble.test.TestUtils.getTransportId;

public class PollerImplTest extends BrambleMockTestCase {

	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final ConnectionManager connectionManager =
			context.mock(ConnectionManager.class);
	private final ConnectionRegistry connectionRegistry =
			context.mock(ConnectionRegistry.class);
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final TransportPropertyManager transportPropertyManager =
			context.mock(TransportPropertyManager.class);
	private final Clock clock = context.mock(Clock.class);
	private final Cancellable cancellable = context.mock(Cancellable.class);

	private final Executor ioExecutor = new ImmediateExecutor();
	private final TransportId transportId = getTransportId();
	private final ContactId contactId = getContactId();
	private final TransportProperties properties = new TransportProperties();
	private final int pollingInterval = 60 * 1000;
	private final long now = System.currentTimeMillis();

	private final PollerImpl poller;

	public PollerImplTest() {

		SecureRandom random = new NeitherSecureNorRandom();
		Executor wakefulIoExecutor = new ImmediateExecutor();
		poller = new PollerImpl(ioExecutor, wakefulIoExecutor, scheduler,
				connectionManager, connectionRegistry, pluginManager,
				transportPropertyManager, random, clock);
	}

	@Test
	public void testConnectOnContactAdded() throws Exception {

		SimplexPlugin simplexPlugin = context.mock(SimplexPlugin.class);
		SimplexPlugin simplexPlugin1 =
				context.mock(SimplexPlugin.class, "simplexPlugin1");
		TransportId simplexId1 = getTransportId();
		List<SimplexPlugin> simplexPlugins =
				asList(simplexPlugin, simplexPlugin1);
		TransportConnectionWriter simplexWriter =
				context.mock(TransportConnectionWriter.class);

		DuplexPlugin duplexPlugin = context.mock(DuplexPlugin.class);
		TransportId duplexId = getTransportId();
		DuplexPlugin duplexPlugin1 =
				context.mock(DuplexPlugin.class, "duplexPlugin1");
		List<DuplexPlugin> duplexPlugins =
				asList(duplexPlugin, duplexPlugin1);
		DuplexTransportConnection duplexConnection =
				context.mock(DuplexTransportConnection.class);

		context.checking(new Expectations() {{

			oneOf(pluginManager).getSimplexPlugins();
			will(returnValue(simplexPlugins));

			oneOf(simplexPlugin).shouldPoll();
			will(returnValue(false));

			oneOf(simplexPlugin1).shouldPoll();
			will(returnValue(true));

			oneOf(simplexPlugin1).getId();
			will(returnValue(simplexId1));
			oneOf(connectionRegistry).isConnected(contactId, simplexId1);
			will(returnValue(false));

			oneOf(transportPropertyManager).getRemoteProperties(contactId,
					simplexId1);
			will(returnValue(properties));

			oneOf(simplexPlugin1).createWriter(properties);
			will(returnValue(simplexWriter));

			oneOf(connectionManager).manageOutgoingConnection(contactId,
					simplexId1, simplexWriter);

			oneOf(pluginManager).getDuplexPlugins();
			will(returnValue(duplexPlugins));

			oneOf(duplexPlugin).shouldPoll();
			will(returnValue(true));

			oneOf(duplexPlugin).getId();
			will(returnValue(duplexId));
			oneOf(connectionRegistry).isConnected(contactId, duplexId);
			will(returnValue(false));

			oneOf(transportPropertyManager).getRemoteProperties(contactId,
					duplexId);
			will(returnValue(properties));

			oneOf(duplexPlugin).createConnection(properties);
			will(returnValue(duplexConnection));

			oneOf(connectionManager).manageOutgoingConnection(contactId,
					duplexId, duplexConnection);

			oneOf(duplexPlugin1).shouldPoll();
			will(returnValue(false));
		}});

		poller.eventOccurred(new ContactAddedEvent(contactId, true));
	}

	@Test
	public void testRescheduleOnOutgoingConnectionClosed() {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));
		}});
		expectReschedule(plugin);

		poller.eventOccurred(new ConnectionClosedEvent(contactId, transportId,
				false, false));
	}

	@Test
	public void testRescheduleAndReconnectOnOutgoingConnectionFailed()
			throws Exception {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);
		DuplexTransportConnection duplexConnection =
				context.mock(DuplexTransportConnection.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));
		}});
		expectReschedule(plugin);
		expectReconnect(plugin, duplexConnection);

		poller.eventOccurred(new ConnectionClosedEvent(contactId, transportId,
				false, true));
	}

	@Test
	public void testRescheduleOnIncomingConnectionClosed() {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));
		}});
		expectReschedule(plugin);

		poller.eventOccurred(new ConnectionClosedEvent(contactId, transportId,
				true, false));
	}

	@Test
	public void testRescheduleOnIncomingConnectionFailed() {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));
		}});
		expectReschedule(plugin);

		poller.eventOccurred(new ConnectionClosedEvent(contactId, transportId,
				true, false));
	}

	@Test
	public void testRescheduleOnConnectionOpened() {
		Plugin plugin = context.mock(Plugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));
		}});

		poller.eventOccurred(new ConnectionOpenedEvent(contactId, transportId,
				false));
	}

	@Test
	public void testRescheduleDoesNotReplaceEarlierTask() {
		Plugin plugin = context.mock(Plugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now + 1));
		}});

		poller.eventOccurred(new ConnectionOpenedEvent(contactId, transportId,
				false));
		poller.eventOccurred(new ConnectionOpenedEvent(contactId, transportId,
				false));
	}

	@Test
	public void testRescheduleReplacesLaterTask() {
		Plugin plugin = context.mock(Plugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now - 1));
			oneOf(cancellable).cancel();
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
		}});

		poller.eventOccurred(new ConnectionOpenedEvent(contactId, transportId,
				false));
		poller.eventOccurred(new ConnectionOpenedEvent(contactId, transportId,
				false));
	}

	@Test
	public void testPollsOnTransportActivated() throws Exception {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L), with(MILLISECONDS));
			will(returnValue(cancellable));
			will(new RunAction());

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));

			oneOf(transportPropertyManager).getRemoteProperties(transportId);
			will(returnValue(singletonMap(contactId, properties)));
			oneOf(connectionRegistry).getConnectedOrBetterContacts(transportId);
			will(returnValue(emptyList()));

			oneOf(plugin).poll(with(collectionOf(
					pairOf(equal(properties), any(ConnectionHandler.class)))));

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(5000L), with(MILLISECONDS));
			will(returnValue(cancellable));
		}});

		poller.eventOccurred(new TransportActiveEvent(transportId));
	}

	@Test
	public void testDoesNotPollIfAllContactsAreConnected() throws Exception {
		DuplexPlugin plugin = context.mock(DuplexPlugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L), with(MILLISECONDS));
			will(returnValue(cancellable));
			will(new RunAction());

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));

			oneOf(transportPropertyManager).getRemoteProperties(transportId);
			will(returnValue(singletonMap(contactId, properties)));
			oneOf(connectionRegistry).getConnectedOrBetterContacts(transportId);
			will(returnValue(singletonList(contactId)));

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(5000L), with(MILLISECONDS));
			will(returnValue(cancellable));
		}});

		poller.eventOccurred(new TransportActiveEvent(transportId));
	}

	@Test
	public void testCancelsPollingOnTransportDeactivated() {
		Plugin plugin = context.mock(Plugin.class);

		context.checking(new Expectations() {{
			allowing(plugin).getId();
			will(returnValue(transportId));

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L), with(MILLISECONDS));
			will(returnValue(cancellable));

			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(5000L), with(MILLISECONDS));
			will(returnValue(cancellable));

			exactly(2).of(cancellable).cancel();
		}});

		poller.eventOccurred(new TransportActiveEvent(transportId));
		poller.eventOccurred(new TransportInactiveEvent(transportId));
	}

	private void expectReschedule(Plugin plugin) {
		context.checking(new Expectations() {{

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(plugin).getPollingInterval();
			will(returnValue(pollingInterval));
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(scheduler).schedule(with(any(Runnable.class)),
					with(ioExecutor), with(0L),
					with(MILLISECONDS));
			will(returnValue(cancellable));
		}});
	}

	private void expectReconnect(DuplexPlugin plugin,
			DuplexTransportConnection duplexConnection) throws Exception {
		context.checking(new Expectations() {{

			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));

			oneOf(plugin).shouldPoll();
			will(returnValue(true));

			oneOf(connectionRegistry).isConnected(contactId, transportId);
			will(returnValue(false));

			oneOf(transportPropertyManager).getRemoteProperties(contactId,
					transportId);
			will(returnValue(properties));

			oneOf(plugin).createConnection(properties);
			will(returnValue(duplexConnection));

			oneOf(connectionManager).manageOutgoingConnection(contactId,
					transportId, duplexConnection);
		}});
	}
}
