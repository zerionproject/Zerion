package org.briarproject.briar.api.channel;

import org.briarproject.bramble.api.db.DbException;

public class ChannelException extends DbException {

	public ChannelException() {
		super();
	}

	public ChannelException(Throwable cause) {
		super(cause);
	}
}
