package org.zerionproject.core.api.sync;

import org.zerionproject.core.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MessageId extends UniqueId {

	public static final String ID_LABEL = "org.zerionproject.core/MESSAGE_ID";

	public static final String BLOCK_LABEL =
			"org.zerionproject.core/MESSAGE_BLOCK";

	public MessageId(byte[] id) {
		super(id);
	}
}
