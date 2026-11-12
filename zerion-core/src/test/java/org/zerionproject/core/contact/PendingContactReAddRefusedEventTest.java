package org.zerionproject.core.contact;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.event.PendingContactReAddRefusedEvent;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PendingContactReAddRefusedEventTest extends BrambleTestCase {

	@Test
	public void testCarriesThePendingContactAuthorAndAlias() {
		PendingContactId id = new PendingContactId(getRandomId());
		Author author = getAuthor();
		PendingContactReAddRefusedEvent e =
				new PendingContactReAddRefusedEvent(id, author, "Alex");
		assertEquals(id, e.getId());
		assertSame(author, e.getAuthor());
		assertEquals("Alex", e.getAlias());
		assertNull(new PendingContactReAddRefusedEvent(id, author, null)
				.getAlias());
	}
}
