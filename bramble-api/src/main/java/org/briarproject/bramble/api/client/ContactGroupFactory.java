package org.briarproject.bramble.api.client;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ContactGroupFactory {

	Group createLocalGroup(ClientId clientId, int majorVersion);

	Group createContactGroup(ClientId clientId, int majorVersion,
			Contact contact);

	Group createContactGroup(ClientId clientId, int majorVersion,
			AuthorId authorId1, AuthorId authorId2);

}
