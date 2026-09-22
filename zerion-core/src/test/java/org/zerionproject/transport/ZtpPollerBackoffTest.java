package org.zerionproject.transport;

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

/**
 * NET-08: a connectivity report that repeats the current state (the screen
 * turning on or off, doze changing) must not forget every contact's dial
 * backoff; only a change from disconnected to connected does.
 */
public class ZtpPollerBackoffTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";

	/** Hands the scheduled sweep back to the test instead of a timer. */
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
		public long dial(int contactId, String peerAddress, boolean fast) {
			dials.incrementAndGet();
			return DIAL_NOT_CONNECTED;
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
		Contact contact = getContact();
		TransportProperties ours = new TransportProperties();
		ours.put(KEY, "aaaa");
		TransportProperties theirs = new TransportProperties();
		theirs.put(KEY, "bbbb");
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(contact)));
			allowing(tpm).getLocalProperties(ID);
			will(returnValue(ours));
			allowing(tpm).getRemoteProperties(
					with(any(ContactId.class)), with(any(TransportId.class)));
			will(returnValue(theirs));
		}});
		poller = new ZtpPoller(Runnable::run, scheduler, contactManager, tpm,
				new NoEvents(), transport);
		poller.start();
	}

	private void report(boolean connected) {
		poller.eventOccurred(new NetworkStatusEvent(
				new NetworkStatus(connected, true, false)));
	}

	@Test
	public void repeatedConnectedReportKeepsTheBackoff() {
		report(true);
		scheduler.runSweep();
		assertEquals("first sweep dials and fails", 1, dials.get());
		scheduler.runSweep();
		assertEquals("backoff holds the next sweep", 1, dials.get());

		report(true);
		scheduler.runSweep();
		assertEquals("a repeated report is not a network change", 1,
				dials.get());

		report(false);
		report(true);
		scheduler.runSweep();
		assertEquals("a real transition clears the backoff", 2,
				dials.get());
	}

	@Test
	public void recoveryClearsTheBackoff() {
		report(true);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		poller.pollNow();
		assertEquals("pollNow always redials", 2, dials.get());
	}
}
