package org.zerionproject.core.connection;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.ConnectionClosedEvent;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Collection;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConnectionRegistryImplTest extends BrambleMockTestCase {

	private final EventBus eventBus = context.mock(EventBus.class);
	private final PluginConfig pluginConfig = context.mock(PluginConfig.class);
	private final InterruptibleConnection conn1 =
			context.mock(InterruptibleConnection.class, "conn1");
	private final InterruptibleConnection conn2 =
			context.mock(InterruptibleConnection.class, "conn2");
	private final InterruptibleConnection conn3 =
			context.mock(InterruptibleConnection.class, "conn3");

	private final ContactId contactId1 = getContactId();
	private final ContactId contactId2 = getContactId();
	private final TransportId transportId1 = getTransportId();
	private final TransportId transportId2 = getTransportId();
	private final TransportId transportId3 = getTransportId();
	private final PendingContactId pendingContactId =
			new PendingContactId(getRandomId());

	private final Priority low =
			new Priority(fromHexString("00000000000000000000000000000000"));
	private final Priority high =
			new Priority(fromHexString("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));

	public ConnectionRegistryImplTest() throws FormatException {

	}

	@Test
	public void testRegisterMultipleConnections() {
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(emptyMap()));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		assertEquals(emptyList(), c.getConnectedContacts(transportId1));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId1));
		assertEquals(emptyList(), c.getConnectedContacts(transportId2));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId2));
		assertEquals(emptyList(), c.getConnectedContacts(transportId3));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId3));
		assertFalse(c.isConnected(contactId1));
		assertFalse(c.isConnected(contactId1, transportId1));
		assertFalse(c.isConnected(contactId1, transportId2));
		assertFalse(c.isConnected(contactId1, transportId3));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn1);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));
		assertTrue(c.isConnected(contactId1));
		assertTrue(c.isConnected(contactId1, transportId1));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn2);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));
		assertTrue(c.isConnected(contactId1));
		assertTrue(c.isConnected(contactId1, transportId1));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionClosedEvent.class)));
		}});
		c.unregisterConnection(contactId1, transportId1, conn1, true, false);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));
		assertTrue(c.isConnected(contactId1));
		assertTrue(c.isConnected(contactId1, transportId1));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionClosedEvent.class)));
			oneOf(eventBus).broadcast(with(any(
					ContactDisconnectedEvent.class)));
		}});
		c.unregisterConnection(contactId1, transportId1, conn2, true, false);
		context.assertIsSatisfied();

		assertEquals(emptyList(), c.getConnectedContacts(transportId1));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId1));
		assertFalse(c.isConnected(contactId1));
		assertFalse(c.isConnected(contactId1, transportId1));

		try {
			c.unregisterConnection(contactId1, transportId1, conn2,
					true, false);
			fail();
		} catch (IllegalArgumentException expected) {

		}
	}

	@Test
	public void testRegisterMultipleContacts() {
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(emptyMap()));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			exactly(3).of(eventBus).broadcast(with(any(
					ConnectionOpenedEvent.class)));
			exactly(2).of(eventBus).broadcast(with(any(
					ContactConnectedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn1);
		c.registerIncomingConnection(contactId2, transportId1, conn2);
		c.registerIncomingConnection(contactId2, transportId2, conn3);
		context.assertIsSatisfied();

		assertTrue(c.isConnected(contactId1));
		assertTrue(c.isConnected(contactId2));

		assertTrue(c.isConnected(contactId1, transportId1));
		assertFalse(c.isConnected(contactId1, transportId2));

		assertTrue(c.isConnected(contactId2, transportId1));
		assertTrue(c.isConnected(contactId2, transportId2));

		Collection<ContactId> connected = c.getConnectedContacts(transportId1);
		assertEquals(2, connected.size());
		assertTrue(connected.contains(contactId1));
		assertTrue(connected.contains(contactId2));

		connected = c.getConnectedOrBetterContacts(transportId1);
		assertEquals(2, connected.size());
		assertTrue(connected.contains(contactId1));
		assertTrue(connected.contains(contactId2));

		assertEquals(singletonList(contactId2),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId2),
				c.getConnectedOrBetterContacts(transportId2));
	}

	@Test
	public void testConnectionsAreNotInterruptedUnlessPriorityIsSet() {

		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(
					singletonMap(transportId1, singletonList(transportId2))));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn1);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(emptyList(), c.getConnectedContacts(transportId2));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId2, conn2, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId3, conn3, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId3));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId3));
	}

	@Test
	public void testNewConnectionIsInterruptedIfOldConnectionUsesBetterTransport() {

		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(
					singletonMap(transportId2, singletonList(transportId1))));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId1, conn1, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(emptyList(), c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(conn2).interruptOutgoingSession();
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId2, conn2, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId3, conn3, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId3));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId3));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionClosedEvent.class)));
		}});
		c.unregisterConnection(contactId1, transportId2, conn2, true, false);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(emptyList(), c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId3));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId3));
	}

	@Test
	public void testOldConnectionIsInterruptedIfNewConnectionUsesBetterTransport() {

		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(
					singletonMap(transportId1, singletonList(transportId2))));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId1, conn1, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(emptyList(), c.getConnectedContacts(transportId2));
		assertEquals(emptyList(), c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(conn1).interruptOutgoingSession();
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId2, conn2, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId3, conn3, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId3));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId3));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionClosedEvent.class)));
		}});
		c.unregisterConnection(contactId1, transportId1, conn1, true, false);
		context.assertIsSatisfied();

		assertEquals(emptyList(), c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId2));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId2));

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId3));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId3));
	}

	@Test
	public void testNewConnectionIsInterruptedIfOldConnectionHasHigherPriority() {
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(emptyMap()));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId1, conn1, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn2);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		context.checking(new Expectations() {{
			oneOf(conn2).interruptOutgoingSession();
		}});
		c.setPriority(contactId1, transportId1, conn2, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		context.checking(new Expectations() {{
			oneOf(conn3).interruptOutgoingSession();
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId1, conn3, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));
	}

	@Test
	public void testOldConnectionIsInterruptedIfNewConnectionHasHigherPriority() {
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(emptyMap()));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
			oneOf(eventBus).broadcast(with(any(ContactConnectedEvent.class)));
		}});
		c.registerOutgoingConnection(contactId1, transportId1, conn1, low);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(ConnectionOpenedEvent.class)));
		}});
		c.registerIncomingConnection(contactId1, transportId1, conn2);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));

		context.checking(new Expectations() {{
			oneOf(conn1).interruptOutgoingSession();
		}});
		c.setPriority(contactId1, transportId1, conn2, high);
		context.assertIsSatisfied();

		assertEquals(singletonList(contactId1),
				c.getConnectedContacts(transportId1));
		assertEquals(singletonList(contactId1),
				c.getConnectedOrBetterContacts(transportId1));
	}

	@Test
	public void testRegisterAndUnregisterPendingContacts() {
		context.checking(new Expectations() {{
			allowing(eventBus).addListener(with(any(EventListener.class)));
			allowing(pluginConfig).getTransportPreferences();
			will(returnValue(emptyMap()));
		}});

		ConnectionRegistry c =
				new ConnectionRegistryImpl(eventBus, pluginConfig);

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(
					RendezvousConnectionOpenedEvent.class)));
		}});
		assertTrue(c.registerConnection(pendingContactId));
		assertFalse(c.registerConnection(pendingContactId));
		context.assertIsSatisfied();

		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(any(
					RendezvousConnectionClosedEvent.class)));
		}});
		c.unregisterConnection(pendingContactId, true);
		context.assertIsSatisfied();

		try {
			c.unregisterConnection(pendingContactId, true);
			fail();
		} catch (IllegalArgumentException expected) {

		}
	}
}
