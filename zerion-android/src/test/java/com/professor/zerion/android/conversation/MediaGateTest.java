package com.professor.zerion.android.conversation;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.AgreementPublicKey;
import org.zerionproject.core.api.identity.AuthorId;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class MediaGateTest {

	private Contact contact(boolean verified, boolean link) {
		return new Contact(new ContactId(1), getAuthor(),
				new AuthorId(getRandomId()), null,
				link ? new AgreementPublicKey(getRandomBytes(32)) : null,
				verified, true, false, null);
	}

	@Test
	public void directlyAddedAndVerifiedContactsMayReceiveMedia() {
		assertTrue(ConversationActivity.mayReceiveMedia(contact(false, true)));
		assertTrue(ConversationActivity.mayReceiveMedia(contact(true, false)));
		assertTrue(ConversationActivity.mayReceiveMedia(contact(true, true)));
	}

	@Test
	public void anUnverifiedIntroducedContactMayNotReceiveMedia() {
		assertFalse(ConversationActivity.mayReceiveMedia(
				contact(false, false)));
	}
}
