package com.professor.zerion.android.grouptr;

import org.junit.Test;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupInviteNameTest {

	@Test
	public void theGroupLearnsTheContactsOwnNameNeverTheLocalAlias() {
		Author author = new Author(new AuthorId(getRandomId()), 1, "Alice",
				new SignaturePublicKey(getRandomBytes(32)));
		Contact withAlias = new Contact(new ContactId(1), author,
				new AuthorId(getRandomId()), "Name only I use", null, true);
		Contact withoutAlias = new Contact(new ContactId(2), author,
				new AuthorId(getRandomId()), null, null, true);

		assertEquals("Alice, Alice",
				GroupTrInviteMembersActivity.nameSentToGroup(withAlias)
						+ ", " + GroupTrInviteMembersActivity
						.nameSentToGroup(withoutAlias));
	}
}
