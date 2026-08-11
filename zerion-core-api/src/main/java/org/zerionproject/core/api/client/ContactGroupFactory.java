package org.zerionproject.core.api.client;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ContactGroupFactory {

	Group createLocalGroup(ClientId clientId, int majorVersion);

	Group createContactGroup(ClientId clientId, int majorVersion,
			Contact contact);

	Group createContactGroup(ClientId clientId, int majorVersion,
			AuthorId authorId1, AuthorId authorId2);

}
