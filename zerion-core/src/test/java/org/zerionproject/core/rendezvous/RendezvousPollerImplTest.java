package org.zerionproject.core.rendezvous;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousPollEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.CaptureArgumentAction;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.ImmediateExecutor;
import org.zerionproject.core.test.PredicateMatcher;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.contact.PendingContactState.ADDING_CONTACT;
import static org.zerionproject.core.api.contact.PendingContactState.FAILED;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.api.contact.PendingContactState.WAITING_FOR_CONNECTION;
import static org.zerionproject.core.rendezvous.RendezvousConstants.FAST_POLLING_INTERVAL_MS;
import static org.zerionproject.core.rendezvous.RendezvousConstants.POLLING_INTERVAL_MS;
import static org.zerionproject.core.rendezvous.RendezvousConstants.RENDEZVOUS_TIMEOUT_MS;
import static org.zerionproject.core.test.CollectionMatcher.collectionOf;
import static org.zerionproject.core.test.PairMatcher.pairOf;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getPendingContact;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.test.TestUtils.getTransportProperties;

public class RendezvousPollerImplTest extends BrambleMockTestCase {

	private final TaskScheduler scheduler = context.mock(TaskScheduler.class);
	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final IdentityManager identityManager =
			context.mock(IdentityManager.class);
	private final TransportCrypto transportCrypto =
			context.mock(TransportCrypto.class);
	private final RendezvousCrypto rendezvousCrypto =
			context.mock(RendezvousCrypto.class);
	private final CryptoComponent crypto =
			context.mock(CryptoComponent.class);
	private final PluginManager pluginManager =
			context.mock(PluginManager.class);
	private final ConnectionManager connectionManager =
			context.mock(ConnectionManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Clock clock = context.mock(Clock.class);
	private final KeyManager keyManager = context.mock(KeyManager.class);
	private final DuplexPlugin plugin = context.mock(DuplexPlugin.class);
	private final KeyMaterialSource keyMaterialSource =
			context.mock(KeyMaterialSource.class);
	private final RendezvousEndpoint rendezvousEndpoint =
			context.mock(RendezvousEndpoint.class);
	private final Cancellable cancellable = context.mock(Cancellable.class);

	private final Executor ioExecutor = new ImmediateExecutor();
	private final PendingContact pendingContact = getPendingContact();
	private final KeyPair handshakeKeyPair =
			new KeyPair(getAgreementPublicKey(), getAgreementPrivateKey());
	private final SecretKey staticMasterKey = getSecretKey();
	private final SecretKey rendezvousKey = getSecretKey();
	private final TransportId transportId = getTransportId();
	private final TransportProperties transportProperties =
			getTransportProperties(3);
	private final boolean alice = new Random().nextBoolean();

	private final RendezvousPollerImpl rendezvousPoller =
			new RendezvousPollerImpl(ioExecutor, scheduler, db,
					identityManager, transportCrypto, rendezvousCrypto,
					crypto, keyManager, pluginManager, connectionManager,
					eventBus, clock);

	@Test
	public void testAddsPendingContactsAndSchedulesPollingAtStartup()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		long beforeExpiry = pendingContact.getTimestamp()
				+ RENDEZVOUS_TIMEOUT_MS - 1000;
		long afterExpiry =
				beforeExpiry + RENDEZVOUS_TIMEOUT_MS + POLLING_INTERVAL_MS;
		AtomicReference<Runnable> capturePollTask;

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(db).getPendingContacts(txn);
			will(returnValue(singletonList(pendingContact)));

			oneOf(clock).currentTimeMillis();
			will(returnValue(beforeExpiry));
			oneOf(eventBus).broadcast(with(new PredicateMatcher<>(
					PendingContactStateChangedEvent.class, e ->
					e.getPendingContactState() == OFFLINE)));
		}});

		expectDeriveRendezvousKey();
		capturePollTask = expectSchedulePolling();

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectPendingContactExpires(afterExpiry);
		expectCancelPolling();

		capturePollTask.get().run();
	}

	@Test
	public void testReanchorsExpiryForOldPendingContactAtStartup()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		long pastOriginalExpiry =
				pendingContact.getTimestamp() + RENDEZVOUS_TIMEOUT_MS;

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(db).getPendingContacts(txn);
			will(returnValue(singletonList(pendingContact)));

			oneOf(clock).currentTimeMillis();
			will(returnValue(pastOriginalExpiry));
			oneOf(eventBus).broadcast(with(new PredicateMatcher<>(
					PendingContactStateChangedEvent.class, e ->
					e.getPendingContactState() == OFFLINE)));
		}});

		expectDeriveRendezvousKey();
		expectSchedulePolling();

		rendezvousPoller.startService();
	}

	@Test
	public void testCreatesAndClosesEndpointsWhenPendingContactIsAddedAndRemoved()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp();

		expectStartupWithNoPendingContacts();

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectGetPlugin();

		rendezvousPoller.eventOccurred(new TransportActiveEvent(transportId));
		context.assertIsSatisfied();

		expectAddPendingContact(beforeExpiry, WAITING_FOR_CONNECTION);
		expectDeriveRendezvousKey();
		expectCreateEndpoint();

		context.checking(new Expectations() {{

			oneOf(rendezvousEndpoint).getRemoteTransportProperties();
			will(returnValue(transportProperties));
			oneOf(clock).currentTimeMillis();
			will(returnValue(beforeExpiry));
			oneOf(eventBus).broadcast(with(any(RendezvousPollEvent.class)));
			oneOf(plugin).poll(with(collectionOf(pairOf(
					equal(transportProperties),
					any(ConnectionHandler.class)))));
		}});

		expectSchedulePolling();

		rendezvousPoller.eventOccurred(
				new PendingContactAddedEvent(pendingContact));
		context.assertIsSatisfied();

		expectCloseEndpoint();
		expectCancelPolling();

		rendezvousPoller.eventOccurred(
				new PendingContactRemovedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(new TransportInactiveEvent(transportId));
	}

	@Test
	public void testCreatesAndClosesEndpointsWhenPendingContactIsAddedAndExpired()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp()
				+ RENDEZVOUS_TIMEOUT_MS - 1000;
		long afterExpiry =
				beforeExpiry + RENDEZVOUS_TIMEOUT_MS + POLLING_INTERVAL_MS;
		AtomicReference<Runnable> capturePollTask;

		expectStartupWithNoPendingContacts();

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectGetPlugin();

		rendezvousPoller.eventOccurred(new TransportActiveEvent(transportId));
		context.assertIsSatisfied();

		expectAddPendingContact(beforeExpiry, WAITING_FOR_CONNECTION);
		expectDeriveRendezvousKey();
		expectCreateEndpoint();

		context.checking(new Expectations() {{

			oneOf(rendezvousEndpoint).getRemoteTransportProperties();
			will(returnValue(transportProperties));
			oneOf(clock).currentTimeMillis();
			will(returnValue(beforeExpiry));
			oneOf(eventBus).broadcast(with(any(RendezvousPollEvent.class)));
			oneOf(plugin).poll(with(collectionOf(pairOf(
					equal(transportProperties),
					any(ConnectionHandler.class)))));
		}});

		capturePollTask = expectSchedulePolling();

		rendezvousPoller.eventOccurred(
				new PendingContactAddedEvent(pendingContact));
		context.assertIsSatisfied();

		expectPendingContactExpires(afterExpiry);
		expectCloseEndpoint();
		expectCancelPolling();

		capturePollTask.get().run();
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(
				new PendingContactRemovedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(new TransportInactiveEvent(transportId));
	}

	@Test
	public void testCreatesAndClosesEndpointsWhenTransportIsActivatedAndDeactivated()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp();

		expectStartupWithNoPendingContacts();

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectAddPendingContact(beforeExpiry, OFFLINE);
		expectDeriveRendezvousKey();
		expectSchedulePolling();

		rendezvousPoller.eventOccurred(
				new PendingContactAddedEvent(pendingContact));
		context.assertIsSatisfied();

		expectGetPlugin();
		expectCreateEndpoint();
		expectStateChangedEvent(WAITING_FOR_CONNECTION);

		rendezvousPoller.eventOccurred(new TransportActiveEvent(transportId));
		context.assertIsSatisfied();

		expectCloseEndpoint();
		expectStateChangedEvent(OFFLINE);

		rendezvousPoller.eventOccurred(new TransportInactiveEvent(transportId));
		context.assertIsSatisfied();

		expectCancelPolling();

		rendezvousPoller.eventOccurred(
				new PendingContactRemovedEvent(pendingContact.getId()));
	}

	@Test
	public void testRendezvousConnectionEvents() throws Exception {
		long beforeExpiry = pendingContact.getTimestamp();

		expectStartupWithPendingContact(beforeExpiry);

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectStateChangedEvent(ADDING_CONTACT);

		rendezvousPoller.eventOccurred(
				new RendezvousConnectionOpenedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		expectStateChangedEvent(WAITING_FOR_CONNECTION);

		rendezvousPoller.eventOccurred(new RendezvousConnectionClosedEvent(
				pendingContact.getId(), false));
	}

	@Test
	public void testPendingContactExpiresBeforeConnection() throws Exception {
		long beforeExpiry = pendingContact.getTimestamp()
				+ RENDEZVOUS_TIMEOUT_MS - 1000;
		long afterExpiry =
				beforeExpiry + RENDEZVOUS_TIMEOUT_MS + POLLING_INTERVAL_MS;

		AtomicReference<Runnable> capturePollTask =
				expectStartupWithPendingContact(beforeExpiry);

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectPendingContactExpires(afterExpiry);
		expectCancelPolling();

		capturePollTask.get().run();
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(
				new RendezvousConnectionOpenedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(new RendezvousConnectionClosedEvent(
				pendingContact.getId(), false));
	}

	@Test
	public void testPendingContactExpiresDuringFailedConnection()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp()
				+ RENDEZVOUS_TIMEOUT_MS - 1000;
		long afterExpiry =
				beforeExpiry + RENDEZVOUS_TIMEOUT_MS + POLLING_INTERVAL_MS;

		AtomicReference<Runnable> capturePollTask =
				expectStartupWithPendingContact(beforeExpiry);

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectStateChangedEvent(ADDING_CONTACT);

		rendezvousPoller.eventOccurred(
				new RendezvousConnectionOpenedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		expectPendingContactExpires(afterExpiry);
		expectCancelPolling();

		capturePollTask.get().run();
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(new RendezvousConnectionClosedEvent(
				pendingContact.getId(), false));
	}

	@Test
	public void testPendingContactExpiresDuringSuccessfulConnection()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp()
				+ RENDEZVOUS_TIMEOUT_MS - 1000;
		long afterExpiry =
				beforeExpiry + RENDEZVOUS_TIMEOUT_MS + POLLING_INTERVAL_MS;

		AtomicReference<Runnable> capturePollTask =
				expectStartupWithPendingContact(beforeExpiry);

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectStateChangedEvent(ADDING_CONTACT);

		rendezvousPoller.eventOccurred(
				new RendezvousConnectionOpenedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		expectPendingContactExpires(afterExpiry);
		expectCancelPolling();

		capturePollTask.get().run();
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(
				new PendingContactRemovedEvent(pendingContact.getId()));
	}

	@Test
	public void testPendingContactRemovedDuringFailedConnection()
			throws Exception {
		long beforeExpiry = pendingContact.getTimestamp();

		expectStartupWithPendingContact(beforeExpiry);

		rendezvousPoller.startService();
		context.assertIsSatisfied();

		expectStateChangedEvent(ADDING_CONTACT);

		rendezvousPoller.eventOccurred(
				new RendezvousConnectionOpenedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		expectCancelPolling();

		rendezvousPoller.eventOccurred(
				new PendingContactRemovedEvent(pendingContact.getId()));
		context.assertIsSatisfied();

		rendezvousPoller.eventOccurred(new RendezvousConnectionClosedEvent(
				pendingContact.getId(), false));
	}

	private AtomicReference<Runnable> expectSchedulePolling() {
		AtomicReference<Runnable> capturePollTask = new AtomicReference<>();

		context.checking(new Expectations() {{
			oneOf(scheduler).scheduleWithFixedDelay(with(any(Runnable.class)),
					with(any(Executor.class)), with(FAST_POLLING_INTERVAL_MS),
					with(FAST_POLLING_INTERVAL_MS), with(MILLISECONDS));
			will(doAll(new CaptureArgumentAction<>(capturePollTask,
					Runnable.class, 0), returnValue(cancellable)));
		}});

		return capturePollTask;
	}

	private void expectCancelPolling() {
		context.checking(new Expectations() {{
			oneOf(cancellable).cancel();
		}});
	}

	private void expectStartupWithNoPendingContacts() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(db).getPendingContacts(txn);
			will(returnValue(emptyList()));
		}});
	}

	private void expectAddPendingContact(long now,
			PendingContactState initialState) {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(eventBus).broadcast(with(new PredicateMatcher<>(
					PendingContactStateChangedEvent.class, e ->
					e.getPendingContactState() == initialState)));
		}});
	}

	private void expectDeriveRendezvousKey() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			will(returnValue(handshakeKeyPair));
			oneOf(transportCrypto).deriveStaticMasterKey(
					pendingContact.getPublicKey(), handshakeKeyPair);
			will(returnValue(staticMasterKey));
			oneOf(rendezvousCrypto).deriveRendezvousKey(staticMasterKey);
			will(returnValue(rendezvousKey));
			oneOf(transportCrypto).isAlice(pendingContact.getPublicKey(),
					handshakeKeyPair);
			will(returnValue(alice));
		}});
	}

	private void expectCreateEndpoint() {
		context.checking(new Expectations() {{
			oneOf(rendezvousCrypto).createKeyMaterialSource(rendezvousKey,
					transportId);
			will(returnValue(keyMaterialSource));
			oneOf(plugin).createRendezvousEndpoint(with(keyMaterialSource),
					with(alice), with(any(ConnectionHandler.class)));
			will(returnValue(rendezvousEndpoint));
		}});
	}

	private void expectGetPlugin() {
		context.checking(new Expectations() {{
			oneOf(pluginManager).getPlugin(transportId);
			will(returnValue(plugin));
			oneOf(plugin).supportsRendezvous();
			will(returnValue(true));
			allowing(plugin).getId();
			will(returnValue(transportId));
		}});
	}

	private AtomicReference<Runnable> expectStartupWithPendingContact(long now)
			throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{

			oneOf(db).transaction(with(true), withDbRunnable(txn));
			oneOf(db).getPendingContacts(txn);
			will(returnValue(singletonList(pendingContact)));

			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
			oneOf(eventBus).broadcast(with(new PredicateMatcher<>(
					PendingContactStateChangedEvent.class, e ->
					e.getPendingContactState() == OFFLINE)));
		}});

		expectDeriveRendezvousKey();
		return expectSchedulePolling();
	}

	private void expectPendingContactExpires(long now) {
		context.checking(new Expectations() {{
			oneOf(clock).currentTimeMillis();
			will(returnValue(now));
		}});

		expectStateChangedEvent(FAILED);
	}

	private void expectStateChangedEvent(PendingContactState state) {
		context.checking(new Expectations() {{
			oneOf(eventBus).broadcast(with(new PredicateMatcher<>(
					PendingContactStateChangedEvent.class, e ->
					e.getPendingContactState() == state)));
		}});
	}

	private void expectCloseEndpoint() throws Exception {
		context.checking(new Expectations() {{
			oneOf(rendezvousEndpoint).close();
		}});
	}
}
