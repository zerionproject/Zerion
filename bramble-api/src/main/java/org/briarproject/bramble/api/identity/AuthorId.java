package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class AuthorId extends UniqueId {

	public static final String LABEL = "org.briarproject.bramble/AUTHOR_ID";

	public AuthorId(byte[] id) {
		super(id);
	}
}
