package org.briarproject.bramble.api.sync;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MessageId extends UniqueId {

	public static final String ID_LABEL = "org.briarproject.bramble/MESSAGE_ID";

	public static final String BLOCK_LABEL =
			"org.briarproject.bramble/MESSAGE_BLOCK";

	public MessageId(byte[] id) {
		super(id);
	}
}
