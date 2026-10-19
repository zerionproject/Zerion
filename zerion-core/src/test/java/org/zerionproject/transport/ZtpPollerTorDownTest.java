package org.zerionproject.transport;

import org.jmock.Expectations;
import org.jmock.Mockery;
import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.zerionproject.tor.TorWrapper.TorState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;

public class ZtpPollerTorDownTest {

	private static final String PENDING =
			"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd";
	private static final String CURRENT =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwd";

	private final AtomicReference<Runnable> next = new AtomicReference<>();
	private final TaskScheduler scheduler = new TaskScheduler() {
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
	};
	private final EventBus events = new EventBus() {
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

	private List<String> hooksAfterADialWhileTorIs(TorState state,
			String dialledAddress) throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		byte[] low = new byte[AuthorId.LENGTH];
		byte[] high = new byte[AuthorId.LENGTH];
		Arrays.fill(high, (byte) 0xFF);
		ContactId id = new ContactId(1);
		Contact c = getContact(id, new Author(new AuthorId(high),
				Author.FORMAT_VERSION, "them", getAuthor().getPublicKey()),
				new AuthorId(low), true);
		TransportProperties props = new TransportProperties();
		props.put(TorConstants.PROP_ONION_V3, dialledAddress);
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(c)));
			allowing(contactManager).getContact(id);
			will(returnValue(c));
			allowing(tpm).getRemoteProperties(id, TorConstants.ID);
			will(returnValue(props));
		}});
		List<String> hooks = Collections.synchronizedList(new ArrayList<>());
		B4OnionRotation rotation = new B4OnionRotation(null, null, null,
				null) {
			@Override
			public String getPendingOnionForContact(ContactId cid) {
				return PENDING;
			}

			@Override
			public void onPendingDialFailed(ContactId cid) {
				hooks.add("failed");
			}

			@Override
			public void onSuccessfulConnect(ContactId cid,
					String dialedOnion) {
				hooks.add("connected");
			}

			@Override
			public void onFallbackDialFailed(ContactId cid) {
				hooks.add("fallbackFailed");
			}
		};
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor() {
			@Override
			public TorState getTorState() {
				return state;
			}
		};
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(),
				Runnable::run, null, null, () -> {
		});
		ZtpPoller poller = new ZtpPoller(Runnable::run, scheduler,
				contactManager, tpm, events, transport, () -> rotation);
		poller.start();
		Runnable sweep = next.getAndSet(null);
		if (sweep != null) sweep.run();
		poller.stop();
		return hooks;
	}

	@Test
	public void whileTorIsDownAContactIsDialledAtMostOnceInThirtySeconds()
			throws Exception {
		Mockery context = new Mockery();
		ContactManager contactManager = context.mock(ContactManager.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		byte[] low = new byte[AuthorId.LENGTH];
		byte[] high = new byte[AuthorId.LENGTH];
		Arrays.fill(high, (byte) 0xFF);
		ContactId id = new ContactId(1);
		Contact c = getContact(id, new Author(new AuthorId(high),
				Author.FORMAT_VERSION, "them", getAuthor().getPublicKey()),
				new AuthorId(low), true);
		TransportProperties props = new TransportProperties();
		props.put(TorConstants.PROP_ONION_V3, CURRENT);
		context.checking(new Expectations() {{
			allowing(contactManager).getContacts();
			will(returnValue(Collections.singletonList(c)));
			allowing(contactManager).getContact(id);
			will(returnValue(c));
			allowing(tpm).getRemoteProperties(id, TorConstants.ID);
			will(returnValue(props));
		}});
		B4OnionRotation rotation = new B4OnionRotation(null, null, null,
				null) {
			@Override
			public String getPendingOnionForContact(ContactId cid) {
				return null;
			}
		};
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor() {
			@Override
			public TorState getTorState() {
				return TorState.CONNECTING;
			}
		};
		AtomicInteger dials = new AtomicInteger();
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(),
				Runnable::run, null, null, () -> {
		}) {
			@Override
			public DialResult dial(int contactId, String peerOnion,
					boolean fast, boolean refusalExpected) {
				dials.incrementAndGet();
				return super.dial(contactId, peerOnion, fast,
						refusalExpected);
			}
		};
		AtomicLong clock = new AtomicLong(1_000_000L);
		ZtpPoller poller = new ZtpPoller(Runnable::run, scheduler,
				contactManager, tpm, events, transport, () -> rotation);
		poller.clock = clock::get;
		poller.start();
		Runnable sweep = next.getAndSet(null);
		sweep.run();
		assertEquals(1, dials.get());
		for (long t = 5_000L; t < ZtpPoller.NOT_ATTEMPTED_WAIT_MS;
				t += 5_000L) {
			clock.addAndGet(5_000L);
			sweep.run();
		}
		assertEquals("a contact was dialled again while Tor was down",
				1, dials.get());
		clock.addAndGet(5_000L);
		sweep.run();
		assertEquals(2, dials.get());
		poller.stop();
	}

	@Test
	public void aDialWhileTorIsDownIsNotAFailedDialToTheAnnouncedOnion()
			throws Exception {
		assertEquals("a dial Tor never made counted against the new onion",
				Collections.emptyList(),
				hooksAfterADialWhileTorIs(TorState.CONNECTING, PENDING));
	}

	@Test
	public void aDialWhileTorIsDownDoesNotCutTheRetryOfTheAnnouncedOnion()
			throws Exception {
		assertEquals("a dial Tor never made counted as a failed fallback",
				Collections.emptyList(),
				hooksAfterADialWhileTorIs(TorState.STARTING, CURRENT));
	}
}
