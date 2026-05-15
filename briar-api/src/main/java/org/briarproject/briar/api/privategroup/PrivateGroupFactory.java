package org.briarproject.briar.api.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PrivateGroupFactory {

	PrivateGroup createPrivateGroup(String name, Author creator);

	PrivateGroup createPrivateGroup(String name, Author creator, byte[] salt);

	PrivateGroup parsePrivateGroup(Group group) throws FormatException;

}
