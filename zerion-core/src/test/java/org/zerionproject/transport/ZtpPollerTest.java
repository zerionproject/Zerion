package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The poller dials only the contacts this side is the designated dialler
 * for, backs a contact off after a failed dial until a recovery or an
 * urgent reason to connect, keeps dialling after a real session, stops
 * dialling when stopped, follows connectivity reports, and restarts a
 * degraded transport once per backoff window.
 */
public class ZtpPollerTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";
	private static final long CONNECTED_SESSION_MS = 10_001L;

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

	private final class RecordingTransport implements OverlayTransport {
		final List<Integer> dials = Collections.synchronizedList(
				new ArrayList<>());
		final List<Boolean> networkEnabled = Collections.synchronizedList(
				new ArrayList<>());
		int restarts = 0;
		volatile long dialResult = DIAL_NOT_CONNECTED;
		volatile boolean degraded = false;

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
			dials.add(contactId);
			return dialResult;
		}

		@Override
		public void setNetworkEnabled(boolean enabled) {
			networkEnabled.add(enabled);
		}

		@Override
		public boolean isNetworkDegraded() {
			return degraded;
		}

		@Override
		public void restartNetwork() {
			restarts++;
		}
	}

	private final ContactId dialled = new ContactId(1);
	private final ContactId dialsUs = new ContactId(2);
	private final RecordingTransport transport = new RecordingTransport();
	private final ManualScheduler scheduler = new ManualScheduler();
	private ZtpPoller poller;

	@Before
	public void setUp() throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		Contact a = getContact(dialled, getAuthor(),
				new AuthorId(getRandomId()), true);
		Contact b = getContact(dialsUs, getAuthor(),
				new AuthorId(getRandomId()), true);
		TransportProperties ours = new TransportProperties();
		ours.put(KEY, "mmmm");
		TransportProperties afterUs = new TransportProperties();
		afterUs.put(KEY, "zzzz");
		TransportProperties beforeUs = new TransportProperties();
		beforeUs.put(KEY, "aaaa");
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Arrays.asList(a, b)));
			allowing(tpm).getLocalProperties(ID);
			will(returnValue(ours));
			allowing(tpm).getRemoteProperties(dialled, ID);
			will(returnValue(afterUs));
			allowing(tpm).getRemoteProperties(dialsUs, ID);
			will(returnValue(beforeUs));
		}});
		poller = new ZtpPoller(Runnable::run, scheduler, contactManager, tpm,
				new NoEvents(), transport);
		poller.start();
	}

	@Test
	public void onlyTheDesignatedDiallerDials() {
		scheduler.runSweep();
		assertEquals(Collections.singletonList(1), transport.dials);
	}

	@Test
	public void aFailedDialIsBackedOffUntilARecovery() {
		scheduler.runSweep();
		scheduler.runSweep();
		scheduler.runSweep();
		assertEquals(1, transport.dials.size());
		poller.pollNow();
		assertEquals(2, transport.dials.size());
		scheduler.runSweep();
		assertEquals(2, transport.dials.size());
		report(false);
		report(true);
		scheduler.runSweep();
		assertEquals(3, transport.dials.size());
	}

	@Test
	public void aRealSessionKeepsTheContactDialable() {
		transport.dialResult = CONNECTED_SESSION_MS;
		scheduler.runSweep();
		scheduler.runSweep();
		assertEquals(2, transport.dials.size());
	}

	@Test
	public void anUrgentReasonDialsThroughTheBackoff() {
		scheduler.runSweep();
		assertEquals(1, transport.dials.size());
		poller.eventOccurred(new ContactAddedEvent(dialled, true));
		assertEquals(2, transport.dials.size());
		poller.eventOccurred(new MessageToAckEvent(dialled));
		assertEquals(3, transport.dials.size());
		poller.eventOccurred(new ContactAddedEvent(dialsUs, true));
		assertEquals(3, transport.dials.size());
	}

	@Test
	public void nothingIsDialledAfterStop() {
		poller.stop();
		scheduler.runSweep();
		poller.pollNow();
		poller.eventOccurred(new ContactAddedEvent(dialled, true));
		assertTrue(transport.dials.isEmpty());
	}

	@Test
	public void connectivityReportsSwitchTheNetwork() {
		report(false);
		report(true);
		report(true);
		assertEquals(Arrays.asList(false, true, true),
				transport.networkEnabled);
	}

	@Test
	public void aDegradedTransportIsRestartedOncePerBackoffWindow() {
		transport.degraded = true;
		scheduler.runSweep();
		scheduler.runSweep();
		scheduler.runSweep();
		assertEquals(1, transport.restarts);
	}

	private void report(boolean connected) {
		poller.eventOccurred(new NetworkStatusEvent(
				new NetworkStatus(connected, true, false)));
	}
}
