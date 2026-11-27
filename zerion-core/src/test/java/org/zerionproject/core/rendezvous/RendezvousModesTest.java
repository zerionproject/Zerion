package org.zerionproject.core.rendezvous;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.ImmediateExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_BURST_INTERVAL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_BURST_PERIOD_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_EAGER_INTERVAL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_EAGER_PERIOD_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_FAST_INTERVAL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_FAST_PERIOD_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIAL_SLOW_INTERVAL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_BOTH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_DIAL_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_PUBLISH_ONLY;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.test.TestUtils.getTransportProperties;

public class RendezvousModesTest extends BrambleMockTestCase {

	private static final long T0 = 1_800_000_000_000L;
	private static final long EARLY =
			RendezvousConstants.FAST_POLLING_INTERVAL_MS / 2;

	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final TransportCrypto transportCrypto =
			context.mock(TransportCrypto.class);
	private final RendezvousCrypto rendezvousCrypto =
			context.mock(RendezvousCrypto.class);
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final KeyMaterialSource keys =
			context.mock(KeyMaterialSource.class);
	private final Cancellable cancellable = context.mock(Cancellable.class);
	private final TransportId transportId = getTransportId();
	private final TransportProperties props = getTransportProperties(2);
	private final KeyPair handshake =
			new KeyPair(getAgreementPublicKey(), getAgreementPrivateKey());
	private final AtomicReference<Runnable> pollTask = new AtomicReference<>();
	private final AtomicInteger published = new AtomicInteger();
	private final AtomicInteger dialOnly = new AtomicInteger();
	private final AtomicInteger polls = new AtomicInteger();
	private final AtomicInteger closed = new AtomicInteger();
	private long now = T0;
	private boolean transportUp = false;
	private final List<PendingContactId> dialed = new ArrayList<>();

	private final Clock clock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long ms) {
		}
	};

	private final RendezvousEndpoint endpoint = new RendezvousEndpoint() {
		@Override
		public TransportProperties getRemoteTransportProperties() {
			return props;
		}

		@Override
		public void close() {
			closed.incrementAndGet();
		}
	};

	private final DuplexPlugin plugin = (DuplexPlugin) Proxy.newProxyInstance(
			DuplexPlugin.class.getClassLoader(),
			new Class<?>[] {DuplexPlugin.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "getId":
						return transportId;
					case "supportsRendezvous":
						return true;
					case "createRendezvousEndpoint":
						published.incrementAndGet();
						return endpoint;
					case "createDialOnlyRendezvousEndpoint":
						dialOnly.incrementAndGet();
						return endpoint;
					case "poll":
						for (Object o : (Collection<?>) args[0]) {
							Object h = ((Pair<?, ?>) o).getSecond();
							Field f = h.getClass()
									.getDeclaredField("pendingContactId");
							f.setAccessible(true);
							dialed.add((PendingContactId) f.get(h));
						}
						polls.addAndGet(((Collection<?>) args[0]).size());
						return null;
					default:
						throw new UnsupportedOperationException(
								method.getName());
				}
			});

	private final RendezvousPollerImpl poller = new RendezvousPollerImpl(
			new ImmediateExecutor(), scheduler, db,
			context.mock(IdentityManager.class), transportCrypto,
			rendezvousCrypto, context.mock(CryptoComponent.class),
			context.mock(KeyManager.class), pluginManager,
			context.mock(ConnectionManager.class), eventBus, clock);

	private PendingContact add(int mode, long expiry) throws Exception {
		return add(mode, expiry, T0);
	}

	private PendingContact add(int mode, long expiry, long delivered)
			throws Exception {
		PendingContact p = new PendingContact(
				new PendingContactId(getRandomId()), getAgreementPublicKey(),
				"alias", delivered, 0, expiry, mode);
		Transaction txn = new Transaction(null, true);
		Transaction write = new Transaction(null, false);
		context.checking(new DbExpectations() {{
			allowing(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));
			allowing(db).transactionWithResult(with(true),
					withDbCallable(txn));
			will(returnValue(handshake));
			allowing(db).transaction(with(false), withDbRunnable(write));
			allowing(db).clearPendingContactOurKeys(write, p.getId());
			allowing(transportCrypto).deriveStaticMasterKey(
					p.getPublicKey(), handshake);
			will(returnValue(getSecretKey()));
			allowing(rendezvousCrypto).deriveRendezvousKey(
					with(any(org.zerionproject.core.api.crypto.SecretKey.class)));
			will(returnValue(getSecretKey()));
			allowing(transportCrypto).isAlice(p.getPublicKey(), handshake);
			will(returnValue(true));
			allowing(rendezvousCrypto).createKeyMaterialSource(
					with(any(org.zerionproject.core.api.crypto.SecretKey.class)),
					with(transportId));
			will(returnValue(keys));
			allowing(eventBus).broadcast(with(any(
					org.zerionproject.core.api.event.Event.class)));
			allowing(scheduler).scheduleWithFixedDelay(
					with(any(Runnable.class)),
					with(any(java.util.concurrent.Executor.class)),
					with(any(Long.class)), with(any(Long.class)),
					with(any(java.util.concurrent.TimeUnit.class)));
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation invocation) {
					pollTask.set((Runnable) invocation.getParameter(0));
					return cancellable;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("captures the poll task");
				}
			});
			allowing(cancellable).cancel();
		}});
		if (!transportUp) {
			poller.eventOccurred(new TransportActiveEvent(transportId));
			transportUp = true;
		}
		poller.eventOccurred(new PendingContactAddedEvent(p));
		return p;
	}

	private void tick(long at) {
		now = at;
		pollTask.get().run();
	}

	@Test
	public void aPublishOnlyRowPublishesAndNeverDials() throws Exception {
		add(RENDEZVOUS_MODE_PUBLISH_ONLY, 0);
		assertEquals(1, published.get());
		assertEquals(0, dialOnly.get());
		assertEquals(0, polls.get());
		for (long t = T0; t < T0 + 3 * DIAL_SLOW_INTERVAL_MS;
				t += DIAL_FAST_INTERVAL_MS / 3) {
			tick(t);
		}
		assertEquals(0, polls.get());
	}

	@Test
	public void aDialOnlyRowPublishesNothingAndDialsOnItsSchedule()
			throws Exception {
		add(RENDEZVOUS_MODE_DIAL_ONLY, T0 + 32L * 24 * 3_600_000);
		assertEquals(0, published.get());
		assertEquals(1, dialOnly.get());
		assertEquals(1, polls.get());
		tick(T0 + 10_000);
		assertEquals(1, polls.get());
		tick(T0 + DIAL_EAGER_INTERVAL_MS - EARLY - 1);
		assertEquals(1, polls.get());
		tick(T0 + DIAL_EAGER_INTERVAL_MS);
		assertEquals(2, polls.get());
		tick(T0 + 2 * DIAL_EAGER_INTERVAL_MS - EARLY - 1);
		assertEquals(2, polls.get());
		tick(T0 + 2 * DIAL_EAGER_INTERVAL_MS);
		assertEquals(3, polls.get());
		long burst = T0 + DIAL_EAGER_PERIOD_MS + 1;
		tick(burst);
		assertEquals(4, polls.get());
		tick(burst + DIAL_EAGER_INTERVAL_MS);
		assertEquals(4, polls.get());
		tick(burst + DIAL_BURST_INTERVAL_MS - EARLY - 1);
		assertEquals(4, polls.get());
		tick(burst + DIAL_BURST_INTERVAL_MS);
		assertEquals(5, polls.get());
		long settled = T0 + DIAL_BURST_PERIOD_MS + 1;
		tick(settled);
		assertEquals(6, polls.get());
		tick(settled + DIAL_BURST_INTERVAL_MS);
		assertEquals(6, polls.get());
		tick(settled + DIAL_FAST_INTERVAL_MS - EARLY - 1);
		assertEquals(6, polls.get());
		tick(settled + DIAL_FAST_INTERVAL_MS);
		assertEquals(7, polls.get());
		long late = T0 + DIAL_FAST_PERIOD_MS + 1;
		tick(late);
		assertEquals(8, polls.get());
		tick(late + DIAL_FAST_INTERVAL_MS);
		assertEquals(8, polls.get());
		tick(late + DIAL_SLOW_INTERVAL_MS - EARLY - 1);
		assertEquals(8, polls.get());
		tick(late + DIAL_SLOW_INTERVAL_MS);
		assertEquals(9, polls.get());
	}

	@Test
	public void aRowExpiresAtItsOwnExpiry() throws Exception {
		long expiry = T0 + 3_600_000;
		add(RENDEZVOUS_MODE_PUBLISH_ONLY, expiry);
		tick(expiry - 1);
		assertEquals(0, closed.get());
		tick(expiry);
		assertEquals(1, closed.get());
	}

	@Test
	public void aRowWithoutExpiryKeepsTheTwoDayWindow() throws Exception {
		add(RENDEZVOUS_MODE_BOTH, 0);
		assertEquals(1, published.get());
		tick(T0 + RendezvousConstants.RENDEZVOUS_TIMEOUT_MS - 1);
		assertEquals(0, closed.get());
		tick(T0 + RendezvousConstants.RENDEZVOUS_TIMEOUT_MS);
		assertEquals(1, closed.get());
	}

	@Test
	public void onlyTheThreeNewestDeliveriesDialEveryTwentySeconds()
			throws Exception {
		long expiry = T0 + 32L * 24 * 3_600_000;
		PendingContact oldest =
				add(RENDEZVOUS_MODE_DIAL_ONLY, expiry, T0 - 3_000);
		PendingContact b = add(RENDEZVOUS_MODE_DIAL_ONLY, expiry, T0 - 2_000);
		PendingContact c = add(RENDEZVOUS_MODE_DIAL_ONLY, expiry, T0 - 1_000);
		PendingContact newest = add(RENDEZVOUS_MODE_DIAL_ONLY, expiry, T0);
		assertEquals(4, polls.get());
		dialed.clear();
		tick(T0 + DIAL_EAGER_INTERVAL_MS);
		assertEquals(7, polls.get());
		assertFalse(dialed.contains(oldest.getId()));
		assertTrue(dialed.contains(b.getId()));
		assertTrue(dialed.contains(c.getId()));
		assertTrue(dialed.contains(newest.getId()));
		tick(T0 + 2 * DIAL_EAGER_INTERVAL_MS);
		assertEquals(10, polls.get());
		tick(T0 + DIAL_BURST_INTERVAL_MS);
		assertEquals(14, polls.get());
	}

	@Test
	public void aClockThatGoesBackDialsAgain() throws Exception {
		add(RENDEZVOUS_MODE_DIAL_ONLY, T0 + 32L * 24 * 3_600_000);
		assertEquals(1, polls.get());
		tick(T0 - 60_000);
		assertEquals(2, polls.get());
		tick(T0 - 60_000 + 10_000);
		assertEquals(2, polls.get());
	}

	@Test
	public void aDeliveryTimeInTheFutureDialsEveryFifteenMinutes()
			throws Exception {
		add(RENDEZVOUS_MODE_DIAL_ONLY, T0 + 32L * 24 * 3_600_000,
				T0 + 3_600_000);
		assertEquals(1, polls.get());
		tick(T0 + DIAL_EAGER_INTERVAL_MS);
		tick(T0 + DIAL_BURST_INTERVAL_MS);
		assertEquals(1, polls.get());
		tick(T0 + DIAL_FAST_INTERVAL_MS);
		assertEquals(2, polls.get());
		assertEquals(DIAL_FAST_INTERVAL_MS,
				RendezvousPollerImpl.dialOnlyInterval(T0 + 1, T0));
	}

	@Test
	public void aDialIsDueHalfATickEarly() {
		assertTrue(RendezvousPollerImpl.dialDue(null, T0, 20_000));
		assertFalse(RendezvousPollerImpl.dialDue(T0, T0 + 20_000 - EARLY - 1,
				20_000));
		assertTrue(RendezvousPollerImpl.dialDue(T0, T0 + 20_000 - EARLY,
				20_000));
		assertTrue(RendezvousPollerImpl.dialDue(T0, T0 - 1, 20_000));
	}

	@Test
	public void theDialIntervalSlowsAfterTwoAndFortyEightHours() {
		assertEquals(DIAL_EAGER_INTERVAL_MS,
				RendezvousPollerImpl.dialOnlyInterval(T0, T0));
		assertEquals(DIAL_EAGER_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_EAGER_PERIOD_MS - 1));
		assertEquals(DIAL_BURST_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_EAGER_PERIOD_MS));
		assertEquals(DIAL_BURST_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_BURST_PERIOD_MS - 1));
		assertEquals(DIAL_FAST_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_BURST_PERIOD_MS));
		assertEquals(DIAL_FAST_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_FAST_PERIOD_MS - 1));
		assertEquals(DIAL_SLOW_INTERVAL_MS, RendezvousPollerImpl
				.dialOnlyInterval(T0, T0 + DIAL_FAST_PERIOD_MS));
	}
}
