package org.zerionproject.transport;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getContact;

public class ZtpPollerSlowDialTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";
	private static final ContactId CONTACT = new ContactId(1);
	private static final long QUICK = ZtpPoller.MAX_BACKOFF_MS * 2;
	private static final long SLOW = ZtpPoller.SLOW_REACH_OUT_MS
			+ ZtpPoller.SLOW_REACH_OUT_JITTER_MS;

	private static final class ManualScheduler implements TaskScheduler {
		final AtomicReference<Runnable> next = new AtomicReference<>();

		@Override
		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			next.set(task);
			return () -> {
			};
		}

		@Override
		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval,
				TimeUnit unit) {
			next.set(task);
			return () -> {
			};
		}

		void runSweep() {
			Runnable r = next.getAndSet(null);
			if (r != null) r.run();
		}
	}

	private static final class NoEvents implements EventBus {
		public void addListener(EventListener l) {
		}

		public void removeListener(EventListener l) {
		}

		public void broadcast(Event e) {
		}
	}

	private final AtomicInteger dials = new AtomicInteger();
	private final AtomicInteger refreshes = new AtomicInteger();
	private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
	private volatile long dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
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
			dials.incrementAndGet();
			long ms = dialResult;
			return new DialResult(ms != DIAL_NOT_CONNECTED, ms);
		}

		@Override
		public void refreshPeerDescriptors() {
			refreshes.incrementAndGet();
		}

		@Override
		public void setNetworkEnabled(boolean enabled) {
		}
	};

	private final ManualScheduler scheduler = new ManualScheduler();
	private ZtpPoller poller;

	@Before
	public void setUp() throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		byte[] low = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		byte[] high = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		java.util.Arrays.fill(high, (byte) 0xFF);
		Contact contact = getContact(CONTACT,
				new org.zerionproject.core.api.identity.Author(
						new org.zerionproject.core.api.identity.AuthorId(high),
						org.zerionproject.core.api.identity.Author.FORMAT_VERSION,
						"them", org.zerionproject.core.test.TestUtils.getAuthor()
								.getPublicKey()),
				new org.zerionproject.core.api.identity.AuthorId(low), true);
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
		poller = new ZtpPoller(Runnable::run, scheduler, contactManager, tpm,
				new NoEvents(), transport);
		poller.clock = now::get;
		poller.start();
		poller.eventOccurred(new NetworkStatusEvent(
				new NetworkStatus(true, true, false)));
	}

	private int sweepsOver(long ms, long step) {
		int before = dials.get();
		for (long t = 0; t < ms; t += step) {
			now.addAndGet(step);
			scheduler.runSweep();
		}
		return dials.get() - before;
	}

	@Test
	public void aContactOfflineForHalfAnHourIsDialedEveryFiveMinutes() {
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertTrue("a contact not reached yet is retried quickly at first",
				sweepsOver(QUICK, 5_000L) >= 3);

		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS);
		int before = dials.get();
		scheduler.runSweep();
		assertEquals(before + 1, dials.get());
		assertEquals("after half an hour a failed dial waits minutes", 0,
				sweepsOver(ZtpPoller.SLOW_REACH_OUT_MS - 5_000L, 5_000L));
		assertEquals("and is then tried once within the next minute", 1,
				sweepsOver(ZtpPoller.SLOW_REACH_OUT_JITTER_MS + 10_000L,
						5_000L));
	}

	@Test
	public void aQueuedMessageStillDialsALongOfflineContactAtOnce() {
		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		poller.eventOccurred(new MessageToAckEvent(CONTACT));
		assertEquals("something to send does not wait for the slow timer",
				2, dials.get());
	}

	@Test
	public void aContactSeenRecentlyKeepsTheQuickRetries() {
		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS);
		dialResult = 20_000L;
		scheduler.runSweep();
		assertEquals(1, dials.get());
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		scheduler.runSweep();
		assertEquals("the dial after a connection that ended fails", 2,
				dials.get());
		assertTrue("a contact seen minutes ago is retried quickly",
				sweepsOver(ZtpPoller.MIN_BACKOFF_MS * 2, 1_000L) >= 1);
	}

	@Test
	public void aLongIncomingSessionThatDropsIsRedialedQuickly() {
		poller.eventOccurred(new ContactConnectedEvent(CONTACT));
		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS + 60_000L);
		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		assertEquals("the drop is redialed at once", 1, dials.get());
		assertTrue("a contact connected until a moment ago keeps the quick"
				+ " retries", sweepsOver(ZtpPoller.MIN_BACKOFF_MS * 2 + 2_000L,
				1_000L) >= 1);
		assertEquals("the second failed dial after the drop purges the"
				+ " descriptors", 1, refreshes.get());
	}

	@Test
	public void aContactAddedLateIsRetriedQuickly() {
		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS + 10L * 60_000L);
		poller.eventOccurred(new ContactAddedEvent(CONTACT, true));
		assertEquals("a new contact is dialed at once", 1, dials.get());
		assertTrue("its first failures are retried on the quick ladder",
				sweepsOver(ZtpPoller.MIN_BACKOFF_MS * 2 + 2_000L,
						1_000L) >= 1);
	}

	@Test
	public void anIncomingConnectionCountsAsSeen() {
		now.addAndGet(ZtpPoller.LONG_OFFLINE_MS);
		poller.eventOccurred(new ContactConnectedEvent(CONTACT));
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertEquals(1, sweepsOver(ZtpPoller.MIN_BACKOFF_MS * 2, 1_000L));
	}
}
