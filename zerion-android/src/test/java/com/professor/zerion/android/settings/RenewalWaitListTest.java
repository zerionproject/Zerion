package com.professor.zerion.android.settings;

import android.content.res.Resources;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.connection.ContactConnectionDiagnostics;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.identity.AuthorId;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RenewalWaitListTest {

	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	private final ContactId named = new ContactId(1);
	private final ContactId aliased = new ContactId(2);
	private final ContactId removed = new ContactId(3);

	private final ContactConnectionDiagnostics diagnostics =
			new ContactConnectionDiagnostics() {
				@Override
				public ContactConnectionSnapshot getSnapshot(ContactId c) {
					throw new UnsupportedOperationException();
				}

				@Override
				public List<ContactId> getContactsHoldingAddressChange() {
					return Arrays.asList(named, removed, aliased);
				}

				@Override
				public long getLastConnectedAgoMs(ContactId c) {
					return c.equals(named) ? 3 * DAY : UNKNOWN;
				}
			};

	private final Contact namedContact = new Contact(named,
			getAuthor(), new AuthorId(getRandomId()), null, null, true);
	private final Contact aliasedContact = new Contact(aliased,
			getAuthor(), new AuthorId(getRandomId()), "Grandma", null, true);

	private final ContactManager contacts = (ContactManager)
			Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {ContactManager.class}, (p, m, args) -> {
						if (!m.getName().equals("getContact")) {
							throw new UnsupportedOperationException();
						}
						ContactId c = (ContactId) args[0];
						if (c.equals(named)) return namedContact;
						if (c.equals(aliased)) return aliasedContact;
						throw new NoSuchContactException();
					});

	@Test
	public void theContactsARenewalWaitsForAreListedByTheirNames() {
		List<RenewalWaitList.Row> rows =
				RenewalWaitList.load(diagnostics, contacts);
		assertEquals(2, rows.size());
		assertEquals(named, rows.get(0).contactId);
		assertEquals(namedContact.getAuthor().getName(), rows.get(0).name);
		assertEquals(3 * DAY, rows.get(0).lastConnectedAgoMs);
		assertEquals(aliased, rows.get(1).contactId);
		assertEquals("Grandma", rows.get(1).name);
		assertEquals(UNKNOWN, rows.get(1).lastConnectedAgoMs);
	}

	@Test
	public void eachRowSaysWhenTheContactWasLastSeen() {
		Resources r = RuntimeEnvironment.getApplication().getResources();
		long now = 1_700_000_000_000L;
		String seen = RenewalWaitList.lastSeen(r, 3 * DAY, now);
		assertTrue(seen, seen.startsWith("Last seen "));
		assertTrue(seen, seen.contains("3"));
		assertEquals(r.getString(R.string.contact_connection_never),
				RenewalWaitList.lastSeen(r, UNKNOWN, now));
	}

	@Test
	public void theNetworkSettingsShowTheContactsAndOpenTheirChats()
			throws Exception {
		String fragment = new String(Files.readAllBytes(new File(
				"src/main/java/com/professor/zerion/android/settings/"
						+ "ConnectionsFragment.java").toPath()),
				StandardCharsets.UTF_8);
		assertTrue(fragment.contains("RenewalWaitList.load("));
		assertTrue(fragment.contains("R.id.renewal_waiting_card"));
		assertTrue(fragment.contains("ConversationActivity.CONTACT_ID"));
		String english = new String(Files.readAllBytes(new File(
				"src/main/res/values/strings.xml").toPath()),
				StandardCharsets.UTF_8);
		int at = english.indexOf("name=\"renewal_waiting_message\"");
		assertTrue(at >= 0);
		String line = english.substring(at, english.indexOf('\n', at))
				.toLowerCase(java.util.Locale.ROOT);
		for (String jargon : new String[] {"onion", "address", "rotat",
				"tor ", "key"}) {
			assertFalse(jargon, line.contains(jargon));
		}
	}
}
