package org.zerionproject.crypto;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.transport.ZerionTransportModule;
import org.zerionproject.wire.ZwfStreamCounter;
import org.jmock.Expectations;
import org.jmock.api.Invocation;
import org.jmock.lib.action.CustomAction;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StreamCounterContactRemovalTest extends BrambleMockTestCase {

	private static final int REUSED = 2;
	private static final int OTHER = 3;
	private static final long SEEN = 900;

	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final List<ContactHook> hooks = new ArrayList<>();
	private final InMemorySettings settings = new InMemorySettings();
	private final SettingsStreamCounterStore store =
			new SettingsStreamCounterStore(settings);

	private ZwfStreamCounter wiredCounter() throws Exception {
		context.checking(new Expectations() {{
			allowing(contactManager).registerContactHook(
					with(any(ContactHook.class)));
			will(new CustomAction("records the hook") {
				@Override
				public Object invoke(Invocation invocation) {
					hooks.add((ContactHook) invocation.getParameter(0));
					return null;
				}
			});
		}});
		for (Method m : ZerionTransportModule.class.getDeclaredMethods()) {
			if (!m.getName().equals("provideStreamCounter")) continue;
			Class<?>[] types = m.getParameterTypes();
			Object[] args = new Object[types.length];
			for (int i = 0; i < types.length; i++) {
				if (types[i] == SettingsStreamCounterStore.class) {
					args[i] = store;
				} else if (types[i] == ContactManager.class) {
					args[i] = contactManager;
				} else {
					throw new AssertionError(types[i].getName());
				}
			}
			m.setAccessible(true);
			return (ZwfStreamCounter) m.invoke(new ZerionTransportModule(),
					args);
		}
		throw new AssertionError("no provideStreamCounter");
	}

	private void removeContact(int id) throws Exception {
		Contact c = getContact(new ContactId(id), getAuthor(),
				new AuthorId(getRandomId()), true);
		Transaction txn = new Transaction(null, false);
		for (ContactHook hook : hooks) hook.removingContact(txn, c);
	}

	private static void talk(ZwfStreamCounter counter, int id) {
		for (long s = 1; s <= SEEN; s++) {
			assertTrue(counter.acceptRecvStreamId(id, s));
		}
		for (long s = 1; s <= 50; s++) {
			assertEquals(s, counter.allocateSendStreamId(id));
		}
	}

	@Test
	public void aNewContactGivenARemovedContactsIdStartsFresh()
			throws Exception {
		ZwfStreamCounter counter = wiredCounter();
		talk(counter, REUSED);
		talk(counter, OTHER);
		removeContact(REUSED);
		assertTrue("the new contact's first stream is accepted",
				counter.acceptRecvStreamId(REUSED, 1));
		assertEquals(1, counter.currentRecvHighWater(REUSED));
		assertEquals("the new contact's send ids start at 1", 1,
				counter.allocateSendStreamId(REUSED));
		assertFalse("the new contact's streams are replay protected",
				counter.acceptRecvStreamId(REUSED, 1));
		assertEquals(SEEN, counter.currentRecvHighWater(OTHER));
		assertFalse(counter.acceptRecvStreamId(OTHER, 1));
		assertEquals(51, counter.allocateSendStreamId(OTHER));
	}

	@Test
	public void theRemovalIsDurableAcrossARestart() throws Exception {
		ZwfStreamCounter counter = wiredCounter();
		talk(counter, REUSED);
		talk(counter, OTHER);
		removeContact(REUSED);
		ZwfStreamCounter restarted = new ZwfStreamCounter(store);
		assertEquals(0, restarted.currentRecvHighWater(REUSED));
		assertTrue("the new contact's first stream is accepted",
				restarted.acceptRecvStreamId(REUSED, 1));
		assertEquals(1, restarted.allocateSendStreamId(REUSED));
		assertFalse(restarted.acceptRecvStreamId(OTHER, 1));
		assertEquals(51, restarted.allocateSendStreamId(OTHER));
	}

	private static class InMemorySettings implements SettingsManager {

		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public synchronized Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) s.putAll(stored);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public synchronized void mergeSettings(Settings s, String namespace) {
			Settings stored = byNamespace.get(namespace);
			if (stored == null) {
				stored = new Settings();
				byNamespace.put(namespace, stored);
			}
			stored.putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	}
}
