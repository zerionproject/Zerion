package org.zerionproject.transport;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactConnectionKeysEvent;
import org.zerionproject.core.api.contact.event.ContactKeysReplacedEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.transport.RootEvolutionTestBed.MemRootKeyStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getContact;
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

	private ZtpSessionProviderImpl registering(SecretKey[] loaded,
			@Nullable Contact known) {
		RootKeyStore holding = new RootKeyStore(null) {
			@Override
			public ContactRootKeys load(ContactId c) {
				return new ContactRootKeys(2, loaded[0], loaded[1], loaded[2],
						loaded[3], true);
			}
		};
		DatabaseComponent db = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					if (known == null) throw new DbException();
					return ((NullableDbCallable<?, ?>) args[1]).call(
							new Transaction(null, true));
				});
		ContactManager contacts = (ContactManager) Proxy.newProxyInstance(
				ContactManager.class.getClassLoader(),
				new Class<?>[] {ContactManager.class},
				(proxy, method, args) -> known);
		return new ZtpSessionProviderImpl(
				new ZwfTagRecogniser(bed.crypto, REPLAY_WINDOW_SIZE), contacts,
				null, bed.sessionFactory,
				new ZwfStreamCounter(new RootEvolutionTestBed.MemCounterStore()),
				db, null, Runnable::run, null, holding, null);
	}

	private SecretKey[] fourKeys() {
		return new SecretKey[] {bed.randomKey(), bed.randomKey(),
				bed.randomKey(), bed.randomKey()};
	}

	private static void assertCleared(SecretKey[] keys) {
		for (int i = 0; i < keys.length; i++) {
			assertArrayEquals("key " + i + " stayed in memory",
					new byte[SecretKey.LENGTH], keys[i].getBytes());
		}
	}

	@Test
	public void rootKeysLoadedForAContactWhoseRolesCannotBeReadAreCleared() {
		SecretKey[] loaded = fourKeys();
		ZtpSessionProviderImpl p = registering(loaded, null);
		p.rootKeysChanged(contact);
		assertCleared(loaded);
		assertFalse(p.peerDials.contains(contact.getInt()));
	}

	@Test
	public void rootKeysLoadedToRegisterAContactAreCleared() {
		SecretKey[] loaded = fourKeys();
		Contact known = getContact();
		boolean dials = Bytes.compare(known.getLocalAuthorId().getBytes(),
				known.getAuthor().getId().getBytes()) < 0;
		ZtpSessionProviderImpl p = registering(loaded, known);
		p.rootKeysChanged(contact);
		assertCleared(loaded);
		assertEquals(!dials, p.peerDials.contains(contact.getInt()));
	}
}
