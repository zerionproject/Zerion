package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnionAuthDiagnosticsTest extends BrambleMockTestCase {

	private final DatabaseComponent db;
	private final OnionAuthStore store;
	private final OnionClientAuthManagerImpl manager;

	public OnionAuthDiagnosticsTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		db = context.mock(DatabaseComponent.class);
		store = context.mock(OnionAuthStore.class);
		TransportPropertyManager tpm =
				context.mock(TransportPropertyManager.class);
		manager = new OnionClientAuthManagerImpl(db,
				context.mock(ClientHelper.class),
				context.mock(ContactGroupFactory.class),
				context.mock(MetadataParser.class), store,
				context.mock(CryptoComponent.class), () -> tpm,
				context.mock(EventBus.class), context.mock(Clock.class),
				Runnable::run, context.mock(TaskScheduler.class));
	}

	private OnionAuthDiagnostics diagnose(OnionAuthRecord r, long serviceGen)
			throws Exception {
		Transaction txn = new Transaction(null, true);
		ServiceRecord svc = new ServiceRecord();
		svc.gen = serviceGen;
		svc.onion = "ownservice";
		svc.privateKey = "ownsecret";
		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(store).load(txn, r.contactId);
			will(returnValue(r));
			oneOf(store).loadService(txn);
			will(returnValue(svc));
		}});
		return manager.diagnostics(r.contactId);
	}

	@Test
	public void aCommitWaitingForTheContactIsReportedWithItsRepairs()
			throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_CONFIRMED;
		r.commitSent = true;
		r.repairs = 2;
		r.peerGen = 4;
		r.peerOnion = "contactservice";
		r.dialPrivateKey = new byte[32];
		OnionAuthDiagnostics d = diagnose(r, 6);
		assertEquals(State.AUTH_CONFIRMED, d.getState());
		assertTrue(d.isCommitSent());
		assertEquals(2, d.getRepairs());
		assertTrue(d.isRepairing());
		assertEquals(6, d.getOwnGeneration());
		assertEquals(4, d.getContactGeneration());
	}

	@Test
	public void repairsStopCountingAsRepairOnceExhausted() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_CONFIRMED;
		r.commitSent = true;
		r.repairs = OnionClientAuthManagerImpl.MAX_REPAIRS;
		assertFalse(diagnose(r, 1).isRepairing());
	}

	@Test
	public void aLockedInContactIsNotRepairing() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_REQUIRED;
		r.commitSent = true;
		r.repairs = 3;
		OnionAuthDiagnostics d = diagnose(r, 1);
		assertEquals(State.AUTH_REQUIRED, d.getState());
		assertFalse(d.isRepairing());
		assertFalse(d.isRenewing());
		assertFalse(d.isReaching());
		assertEquals(0, d.getReachRepairs());
	}

	@Test
	public void aLockedInContactBeingReKeyedIsRenewing() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_REQUIRED;
		r.rekeying = true;
		r.commitSent = true;
		r.openDials = 2;
		r.peerGen = 5;
		OnionAuthDiagnostics d = diagnose(r, 2);
		assertTrue(d.isRenewing());
		assertFalse(d.isReaching());
		assertFalse(d.isRepairing());
		assertEquals(5, d.getContactGeneration());
	}

	@Test
	public void silenceAndOpenRepairDialsAreCounted() throws Exception {
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_REQUIRED;
		r.silenceRepairs = 2;
		r.openDials = 1;
		r.nextSilenceRepairMs = 123;
		r.nextOpenDialMs = 456;
		OnionAuthDiagnostics d = diagnose(r, 1);
		assertTrue(d.isReaching());
		assertFalse(d.isRenewing());
		assertEquals(3, d.getReachRepairs());
	}
}
