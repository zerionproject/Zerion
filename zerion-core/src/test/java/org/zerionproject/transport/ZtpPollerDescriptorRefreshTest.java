package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import static org.zerionproject.core.test.TestUtils.getContact;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZtpPollerDescriptorRefreshTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";

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
	private final java.util.concurrent.atomic.AtomicLong now =
			new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
	private volatile long dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
	private volatile boolean torDown = false;
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
			if (torDown) return DialResult.NOT_ATTEMPTED;
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
		Contact contact = getContact(new ContactId(1),
				new org.zerionproject.core.api.identity.Author(
						new org.zerionproject.core.api.identity.AuthorId(high),
						org.zerionproject.core.api.identity.Author.FORMAT_VERSION,
						"them", org.zerionproject.core.test.TestUtils.getAuthor()
								.getPublicKey()),
				new org.zerionproject.core.api.identity.AuthorId(low), true);
		TransportProperties ours = new TransportProperties();
		ours.put(KEY, "aaaa");
		TransportProperties theirs = new TransportProperties();
		theirs.put(KEY, "bbbb");
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(contact)));
			allowing(contactManager).getContact(with(any(ContactId.class)));
			will(returnValue(contact));
			allowing(tpm).getLocalProperties(ID);
			will(returnValue(ours));
			allowing(tpm).getRemoteProperties(
					with(any(ContactId.class)), with(any(TransportId.class)));
			will(returnValue(theirs));
		}});
		poller = new ZtpPoller(Runnable::run, scheduler, contactManager, tpm,
				new NoEvents(), transport);
		poller.clock = now::get;
		poller.start();
	}

	private void report(boolean connected) {
		poller.eventOccurred(new NetworkStatusEvent(
				new NetworkStatus(connected, true, false)));
	}

	@Test
	public void twoFailedDialsAfterADropRefreshThePeerDescriptorsOnce() {
		dialResult = 20_000L;
		report(true);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		poller.dialNow(1);
		assertEquals("one failure is not yet a stale descriptor", 0,
				refreshes.get());
		poller.dialNow(1);
		assertEquals("the second failure purges the cache", 1,
				refreshes.get());
		scheduler.runSweep();
		assertEquals("the next sweep redials without backoff", 4,
				dials.get());
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals("later failures do not purge again within the interval",
				1, refreshes.get());
		now.addAndGet(ZtpPoller.SECOND_REFRESH_AFTER_MS);
		poller.dialNow(1);
		assertEquals("a dial that still fails two minutes later purges once"
				+ " more, since the peer may have uploaded late", 2,
				refreshes.get());
		now.addAndGet(ZtpPoller.SECOND_REFRESH_AFTER_MS);
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals("two purges per drop at most", 2, refreshes.get());
		dialResult = 20_000L;
		poller.dialNow(1);
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals("a new drop starts a new budget", 3, refreshes.get());
	}

	@Test
	public void dialsTorNeverMadeLeaveTheRefreshAfterADropUnused() {
		dialResult = 20_000L;
		report(true);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		torDown = true;
		for (int i = 0; i < 4; i++) poller.dialNow(1);
		assertEquals("dials Tor never made used up the refresh", 0,
				refreshes.get());
		poller.wallClock = now::get;
		assertEquals("a dial Tor never made did not set the 30 second wait",
				now.get() + ZtpPoller.NOT_ATTEMPTED_WAIT_MS,
				poller.diagnostics(1).getNextDialAtMs());
		torDown = false;
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals(1, refreshes.get());
	}

	@Test
	public void aRemovedContactLeavesNothingBehind() {
		dialResult = 20_000L;
		report(true);
		scheduler.runSweep();
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		poller.dialNow(1);
		assertTrue("a failed dial leaves a next attempt",
				poller.diagnostics(1).getNextDialAtMs() > 0);
		poller.eventOccurred(new ContactRemovedEvent(new ContactId(1)));
		assertEquals("no next attempt is kept for a removed contact", 0L,
				poller.diagnostics(1).getNextDialAtMs());
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals("no descriptor refresh is owed for a removed contact",
				0, refreshes.get());
	}

	@Test
	public void aDropJustBeforeOurOwnBlipStillGetsItsRefresh() {
		dialResult = 20_000L;
		report(true);
		scheduler.runSweep();
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		poller.pollNow();
		poller.dialNow(1);
		assertEquals("a peer drop is still a peer drop after our own blip",
				1, refreshes.get());
	}

	@Test
	public void failuresWithoutAnEarlierConnectionDoNotRefresh() {
		report(true);
		scheduler.runSweep();
		poller.dialNow(1);
		poller.dialNow(1);
		poller.dialNow(1);
		assertEquals(4, dials.get());
		assertEquals("a peer that never connected is simply offline", 0,
				refreshes.get());
	}
}
