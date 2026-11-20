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
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.TorActivitySink.Activity;
import org.zerionproject.tor.TorWakePolicy;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getContact;

/**
 * While the device is quiet (Tor wake lock released, app in the background)
 * a contact that has not been connected for five minutes is retried at the
 * slow cadence, so failing dials do not keep the device awake.
 */
public class ZtpPollerQuietTest {

	private static final TransportId ID = new TransportId("t");
	private static final String KEY = "onion3";
	private static final ContactId CONTACT = new ContactId(1);
	private static final long QUICK = ZtpPoller.MAX_BACKOFF_MS * 2;

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
	private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
	private final AtomicBoolean quiet = new AtomicBoolean(false);
	private volatile long dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
	private volatile Runnable duringDial = () -> {
	};
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
			duringDial.run();
			long ms = dialResult;
			return new DialResult(ms != DIAL_NOT_CONNECTED, ms);
		}

		@Override
		public void refreshPeerDescriptors() {
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
		poller.setQuietCheck(quiet::get);
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
	public void aQuietPhoneRetriesAnUnreachedContactSlowlyAfterFiveMinutes() {
		quiet.set(true);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertTrue("within the first five minutes the retries stay quick",
				sweepsOver(QUICK, 5_000L) >= 3);

		now.addAndGet(ZtpPoller.QUIET_OFFLINE_MS);
		int before = dials.get();
		scheduler.runSweep();
		assertEquals(before + 1, dials.get());
		assertEquals("quiet and unseen for five minutes: a failed dial waits",
				0, sweepsOver(ZtpPoller.SLOW_REACH_OUT_MS - 5_000L, 5_000L));
		assertEquals("and is then tried once within the next minute", 1,
				sweepsOver(ZtpPoller.SLOW_REACH_OUT_JITTER_MS + 10_000L,
						5_000L));
	}

	@Test
	public void aPhoneThatIsNotQuietKeepsTheQuickRetries() {
		quiet.set(false);
		now.addAndGet(ZtpPoller.QUIET_OFFLINE_MS * 2);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertTrue("awake or in the foreground, retries stay quick",
				sweepsOver(QUICK, 5_000L) >= 3);
	}

	@Test
	public void aContactSeenRecentlyIsRetriedQuicklyEvenWhenQuiet() {
		quiet.set(true);
		now.addAndGet(ZtpPoller.QUIET_OFFLINE_MS * 2);
		dialResult = 20_000L;
		scheduler.runSweep();
		assertEquals(1, dials.get());
		dialResult = OverlayTransport.DIAL_NOT_CONNECTED;
		scheduler.runSweep();
		assertEquals("the dial after a connection that ended fails", 2,
				dials.get());
		assertTrue("a contact connected minutes ago is retried quickly",
				sweepsOver(QUICK, 5_000L) >= 3);
	}

	@Test
	public void aRealPolicyStaysQuietThroughTheDialAndItsTorEvents() {
		TaskScheduler noScheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				return () -> {
				};
			}
		};
		TorWakePolicy policy =
				new TorWakePolicy(noScheduler, Runnable::run, now::get);
		policy.onNetworkEnabled(true);
		policy.onBootstrapProgress(100);
		poller.setQuietCheck(policy::isQuiet);
		duringDial = () -> {
			policy.noteActivity(Activity.CONNECTION_ATTEMPT);
			policy.onTorEvent();
			policy.onDescriptorUpload("a");
			policy.onDescriptorUploadFinished("a");
		};
		now.addAndGet(ZtpPoller.QUIET_OFFLINE_MS);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertTrue("the policy is quiet in the background", policy.isQuiet());
		assertEquals("a failed dial to an unreached contact waits minutes",
				0, sweepsOver(ZtpPoller.SLOW_REACH_OUT_MS - 5_000L, 5_000L));
		assertEquals(1, sweepsOver(
				ZtpPoller.SLOW_REACH_OUT_JITTER_MS + 10_000L, 5_000L));

		policy.setForeground(true);
		scheduler.runSweep();
		long longestQuick = ZtpPoller.MAX_BACKOFF_MS + ZtpPoller.MAX_BACKOFF_MS / 5;
		assertTrue("in the foreground the retries are quick again, even with"
						+ " the fail streak at its longest backoff",
				sweepsOver(4 * longestQuick, 5_000L) >= 3);
	}

	@Test
	public void aSlowWaitedContactIsDialledOnTheFirstSweepAfterQuietEnds() {
		quiet.set(true);
		now.addAndGet(ZtpPoller.QUIET_OFFLINE_MS);
		scheduler.runSweep();
		assertEquals(1, dials.get());
		assertEquals("the slow wait is in force", 0,
				sweepsOver(60_000L, 5_000L));
		quiet.set(false);
		now.addAndGet(5_000L);
		scheduler.runSweep();
		assertEquals("the first sweep after quiet ended dials", 2,
				dials.get());
	}

	@Test
	public void becomingQuietLaterSwitchesToTheSlowCadence() {
		quiet.set(false);
		scheduler.runSweep();
		assertTrue(sweepsOver(ZtpPoller.QUIET_OFFLINE_MS, 5_000L) >= 3);
		quiet.set(true);
		int before = dials.get();
		sweepsOver(QUICK, 5_000L);
		assertTrue("at most one more dial before the slow wait takes over",
				dials.get() - before <= 1);
	}
}
