package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.test.TestUtils;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.core.test.TestUtils.getContact;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ZtpPollerDiagnosticsTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";

	private final AtomicReference<Runnable> sweep = new AtomicReference<>();
	private final TaskScheduler scheduler = new TaskScheduler() {
		@Override
		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			sweep.set(task);
			return () -> {
			};
		}

		@Override
		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval,
				TimeUnit unit) {
			sweep.set(task);
			return () -> {
			};
		}
	};
	private final EventBus noEvents = new EventBus() {
		@Override
		public void addListener(EventListener l) {
		}

		@Override
		public void removeListener(EventListener l) {
		}

		@Override
		public void broadcast(Event e) {
		}
	};
	private final AtomicReference<ZtpDialDiagnostics> duringDial =
			new AtomicReference<>();
	private ZtpPoller poller;
	private final OverlayTransport transport = new OverlayTransport() {
		@Override
		public TransportId getTransportId() {
			return ID;
		}

		@Override
		public String getAddressPropertyKey() {
			return KEY;
		}

		@Override
		public DialResult dial(int contactId, String peerAddress,
				boolean fast) {
			duringDial.set(poller.diagnostics(contactId));
			return DialResult.NOT_CONNECTED;
		}

		@Override
		public void setNetworkEnabled(boolean enabled) {
		}
	};
	private ZtpPollerFactory factory;

	@Before
	public void setUp() throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		byte[] low = new byte[UniqueId.LENGTH];
		byte[] high = new byte[UniqueId.LENGTH];
		Arrays.fill(high, (byte) 0xFF);
		Contact contact = getContact(new ContactId(1),
				new Author(new AuthorId(high), Author.FORMAT_VERSION, "them",
						TestUtils.getAuthor().getPublicKey()),
				new AuthorId(low), true);
		TransportProperties theirs = new TransportProperties();
		theirs.put(KEY, "bbbb");
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(contact)));
			allowing(contactManager).getContact(with(any(ContactId.class)));
			will(returnValue(contact));
			allowing(tpm).getRemoteProperties(
					with(any(ContactId.class)), with(any(TransportId.class)));
			will(returnValue(theirs));
		}});
		factory = new ZtpPollerFactory(Runnable::run, scheduler,
				contactManager, tpm, noEvents, () -> null);
		poller = factory.create(transport);
		poller.start();
	}

	@Test
	public void theFactoryKeepsThePollerOfEachTransport() {
		assertSame(poller, factory.getPoller(ID));
		assertNull(factory.getPoller(new TransportId("other")));
	}

	@Test
	public void diagnosticsShowDialingAndTheNextAttempt() {
		ZtpDialDiagnostics before = poller.diagnostics(1);
		assertFalse(before.isDialing());
		assertEquals(0L, before.getNextDialAtMs());

		long start = System.currentTimeMillis();
		sweep.getAndSet(null).run();
		assertTrue(duringDial.get().isDialing());

		ZtpDialDiagnostics after = poller.diagnostics(1);
		assertFalse(after.isDialing());
		assertTrue(after.getNextDialAtMs() >= start + 5_000);
		assertTrue(after.getNextDialAtMs()
				<= System.currentTimeMillis() + 6_000);
		assertEquals(0L, poller.diagnostics(2).getNextDialAtMs());
	}
}
