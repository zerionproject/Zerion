package org.zerionproject.app.api.privategroup;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PrivateGroupFactory {

	PrivateGroup createPrivateGroup(String name, Author creator);

	PrivateGroup createPrivateGroup(String name, Author creator, byte[] salt);

	PrivateGroup parsePrivateGroup(Group group) throws FormatException;

}
