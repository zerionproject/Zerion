package org.briarproject.briar.api.client;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class SessionId extends UniqueId {

	public SessionId(byte[] id) {
		super(id);
	}
}
