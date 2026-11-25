package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.event.ContactConnectionKeysEvent;
import org.zerionproject.core.api.contact.event.ContactKeysReplacedEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.transport.RootEvolutionTestBed.MemRootKeyStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

public class ZtpSessionProviderContactStateTest {

	private final ContactId contact = new ContactId(7);
	private final ContactId other = new ContactId(8);
	private RootEvolutionTestBed bed;
	private MemRootKeyStore store;
	private RootEvolutionManager manager;
	private ZtpSessionProviderImpl provider;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		store = new MemRootKeyStore();
		manager = new RootEvolutionManager(store, bed.crypto, bed.mlKem,
				bed.clock::get);
		provider = new ZtpSessionProviderImpl(
				new ZwfTagRecogniser(bed.crypto, REPLAY_WINDOW_SIZE), null,
				null, null,
				new ZwfStreamCounter(new RootEvolutionTestBed.MemCounterStore()),
				null, null, task -> {
				}, null, store, manager);
		for (ContactId c : new ContactId[] {contact, other}) {
			store.put(c, ContactRootKeys.atPairing(bed.randomKey()));
			manager.peerGreeted(c, true);
			manager.stalledBesideAnother(c);
		}
	}

	private void assertForgotten(Event e) {
		provider.eventOccurred(e);
		assertFalse("a pause of the peer is still remembered",
				manager.peerPauseRemembered(contact));
		manager.connectionEnded(contact, false);
		assertEquals("a stall noted beside another connection was counted",
				0, store.stalls(contact)[0]);
		assertTrue("another contact was forgotten too",
				manager.peerPauseRemembered(other));
		manager.connectionEnded(other, false);
		assertEquals(1, store.stalls(other)[0]);
	}

	@Test
	public void removingAContactDropsWhatIsRememberedOfItsConnections() {
		assertForgotten(new ContactRemovedEvent(contact));
	}

	@Test
	public void replacingAContactsKeysDropsWhatIsRememberedOfItsConnections() {
		assertForgotten(new ContactKeysReplacedEvent(contact));
	}

	@Test
	public void clearingTheOutOfSyncMarkKeepsWhatIsRememberedOfTheContact() {
		provider.eventOccurred(new ContactConnectionKeysEvent(contact, false));
		assertTrue(manager.peerPauseRemembered(contact));
		manager.connectionEnded(contact, false);
		assertEquals(1, store.stalls(contact)[0]);
	}
}
