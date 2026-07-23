package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class AuthorId extends UniqueId {

	public static final String LABEL = "org.zerionproject.core/AUTHOR_ID";

	public AuthorId(byte[] id) {
		super(id);
	}
}
