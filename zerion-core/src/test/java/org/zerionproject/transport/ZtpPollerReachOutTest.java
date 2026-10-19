package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.sync.event.MessageToAckEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.core.test.TestUtils.getContact;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZtpPollerReachOutTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";
	private static final ContactId CONTACT = new ContactId(1);
	private static final long HEAD_START =
			ZtpPoller.REACH_OUT_DELAY_MS + ZtpPoller.REACH_OUT_JITTER_MS;
	private static final long SLOW =
			ZtpPoller.SLOW_REACH_OUT_MS + ZtpPoller.SLOW_REACH_OUT_JITTER_MS;
	private static final long ARRIVAL = ZtpPoller.ARRIVAL_REFRESH_DELAY_MS
			+ ZtpPoller.ARRIVAL_REFRESH_JITTER_MS;

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
	private volatile boolean connected = false;
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

	private ZtpPoller poller(boolean weDial) throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		byte[] low = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		byte[] high = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		Arrays.fill(high, (byte) 0xFF);
		byte[] ours = weDial ? low : high;
		byte[] theirs = weDial ? high : low;
		Contact contact = getContact(CONTACT,
				new org.zerionproject.core.api.identity.Author(
						new org.zerionproject.core.api.identity.AuthorId(
								theirs),
						org.zerionproject.core.api.identity.Author.FORMAT_VERSION,
						"them", org.zerionproject.core.test.TestUtils.getAuthor()
								.getPublicKey()),
				new org.zerionproject.core.api.identity.AuthorId(ours), true);
		TransportProperties theirProperties = new TransportProperties();
		theirProperties.put(KEY, "bbbb");
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(contact)));
			allowing(contactManager).getContact(with(any(ContactId.class)));
			will(returnValue(contact));
			allowing(tpm).getRemoteProperties(
					with(any(ContactId.class)), with(any(TransportId.class)));
			will(returnValue(theirProperties));
		}});
		ZtpPoller poller = new ZtpPoller(Runnable::run, scheduler,
				contactManager, tpm, new NoEvents(), transport);
		poller.clock = now::get;
		poller.setConnectedCheck(c -> connected);
		poller.start();
		return poller;
	}

	@Test
	public void aContactThatDialsUsIsDialedOnceItsHeadStartHasPassed()
			throws Exception {
		ZtpPoller poller = poller(false);
		poller.pollNow();
		scheduler.runSweep();
		assertEquals("the other side dials first", 0, dials.get());

		now.addAndGet(HEAD_START);
		scheduler.runSweep();
		assertEquals("after the head start this side reaches out", 1,
				dials.get());
	}

	@Test
	public void afterItsWindowReachingOutSlowsDown() throws Exception {
		ZtpPoller poller = poller(false);
		poller.pollNow();
		now.addAndGet(ZtpPoller.REACH_OUT_WINDOW_MS);
		scheduler.runSweep();
		now.addAndGet(60_000L);
		scheduler.runSweep();
		assertEquals(0, dials.get());

		now.addAndGet(SLOW);
		scheduler.runSweep();
		assertEquals("a contact never reached is still tried now and then",
				1, dials.get());
		scheduler.runSweep();
		assertEquals(1, dials.get());
		now.addAndGet(SLOW);
		scheduler.runSweep();
		assertEquals(2, dials.get());
	}

	@Test
	public void slowReachingOutStopsWhileTheContactIsConnected()
			throws Exception {
		poller(false);
		scheduler.runSweep();
		connected = true;
		now.addAndGet(SLOW);
		scheduler.runSweep();
		now.addAndGet(SLOW);
		scheduler.runSweep();
		assertEquals(0, dials.get());
	}

	@Test
	public void theDialingSideDoesNotAddSlowReachOuts() throws Exception {
		poller(true);
		connected = true;
		for (int i = 0; i < 4; i++) {
			now.addAndGet(SLOW);
			scheduler.runSweep();
		}
		assertEquals(0, dials.get());
	}

	@Test
	public void reachingOutEndsOnceTheContactIsConnected() throws Exception {
		ZtpPoller poller = poller(false);
		poller.pollNow();
		now.addAndGet(HEAD_START);
		connected = true;
		scheduler.runSweep();
		connected = false;
		now.addAndGet(60_000L);
		scheduler.runSweep();
		assertEquals("a reach out ended by a connection does not resume",
				0, dials.get());
	}

	@Test
	public void aConnectedContactIsNotDialedAgain() throws Exception {
		ZtpPoller poller = poller(true);
		connected = true;
		poller.pollNow();
		scheduler.runSweep();
		poller.eventOccurred(new MessageToAckEvent(CONTACT));
		assertEquals(0, dials.get());

		connected = false;
		poller.eventOccurred(new MessageToAckEvent(CONTACT));
		assertEquals(1, dials.get());
	}

	@Test
	public void aProbeDialIsMadeWhileConnected() throws Exception {
		ZtpPoller poller = poller(true);
		connected = true;
		poller.dialNow(CONTACT.getInt());
		assertEquals("a client authorization probe needs its own connection",
				1, dials.get());
	}

	@Test
	public void aConnectionThatLastedClearsTheBackoff() throws Exception {
		ZtpPoller poller = poller(true);
		scheduler.runSweep();
		scheduler.runSweep();
		assertEquals("the failed dial waits for its backoff", 1, dials.get());

		poller.eventOccurred(new ContactConnectedEvent(CONTACT));
		now.addAndGet(ZtpPoller.MIN_CONNECTED_MS);
		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		assertEquals("a connection that lasted is redialed at once", 2,
				dials.get());
	}

	@Test
	public void aConnectionThatEndsAtOnceKeepsTheBackoff() throws Exception {
		ZtpPoller poller = poller(true);
		scheduler.runSweep();
		poller.eventOccurred(new ContactConnectedEvent(CONTACT));
		now.addAndGet(1_000L);
		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		scheduler.runSweep();
		assertEquals("a flapping connection waits for its backoff", 1,
				dials.get());
	}

	@Test
	public void aConnectionThatEndsAtOnceOpensNoReachOut() throws Exception {
		ZtpPoller poller = poller(false);
		poller.eventOccurred(new ContactConnectedEvent(CONTACT));
		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		now.addAndGet(HEAD_START);
		scheduler.runSweep();
		assertEquals(0, dials.get());
	}

	@Test
	public void theNextDialIsReportedInWallClockTime() throws Exception {
		ZtpPoller poller = poller(true);
		poller.wallClock = () -> 5_000_000L;
		scheduler.runSweep();
		long next = poller.diagnostics(CONTACT.getInt()).getNextDialAtMs();
		assertTrue(String.valueOf(next), next >= 5_005_000L
				&& next <= 5_006_000L);
	}

	@Test
	public void aDisconnectIsRedialedAtOnceByTheDialingSide()
			throws Exception {
		ZtpPoller poller = poller(true);
		scheduler.runSweep();
		assertEquals(1, dials.get());

		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		assertEquals(2, dials.get());
	}

	@Test
	public void aDisconnectOpensAReachOutOnTheOtherSide() throws Exception {
		ZtpPoller poller = poller(false);
		poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
		scheduler.runSweep();
		assertEquals(0, dials.get());

		now.addAndGet(HEAD_START);
		scheduler.runSweep();
		assertEquals(1, dials.get());
	}

	@Test
	public void aDisconnectDuringASweepKeepsItsReachOut() throws Exception {
		ZtpPoller poller = poller(false);
		poller.setConnectedCheck(c -> {
			poller.setConnectedCheck(d -> false);
			poller.eventOccurred(new ContactDisconnectedEvent(CONTACT));
			return true;
		});
		scheduler.runSweep();
		assertEquals(0, dials.get());

		now.addAndGet(HEAD_START);
		scheduler.runSweep();
		assertEquals("the reach out opened by the disconnect survives the sweep",
				1, dials.get());
	}

	@Test
	public void descriptorsAreRefreshedForAContactNotYetReachedAfterAStart()
			throws Exception {
		ZtpPoller poller = poller(true);
		poller.pollNow();
		assertEquals("the start dials once", 1, dials.get());
		assertEquals(0, refreshes.get());
		poller.dialNow(CONTACT.getInt());
		assertEquals("the first dials after a start keep their circuits", 0,
				refreshes.get());
		now.addAndGet(ARRIVAL);
		poller.dialNow(CONTACT.getInt());
		assertEquals("a start counts as recent for a contact not reached yet",
				1, refreshes.get());

		now.addAndGet(ZtpPoller.REACH_OUT_WINDOW_MS);
		poller.dialNow(CONTACT.getInt());
		poller.dialNow(CONTACT.getInt());
		assertEquals("after the window an unreached contact is offline", 1,
				refreshes.get());
	}

	@Test
	public void anUnreachedContactRefreshesOncePerStart() throws Exception {
		ZtpPoller poller = poller(true);
		poller.pollNow();
		now.addAndGet(ARRIVAL);
		poller.dialNow(CONTACT.getInt());
		poller.dialNow(CONTACT.getInt());
		assertEquals(1, refreshes.get());

		now.addAndGet(ZtpPoller.MIN_REFRESH_INTERVAL_MS);
		poller.dialNow(CONTACT.getInt());
		poller.dialNow(CONTACT.getInt());
		assertEquals("a contact that is simply offline is not refreshed again",
				1, refreshes.get());

		poller.pollNow();
		now.addAndGet(ARRIVAL);
		poller.dialNow(CONTACT.getInt());
		poller.dialNow(CONTACT.getInt());
		assertEquals("a new start allows one more refresh", 2, refreshes.get());
	}
}
