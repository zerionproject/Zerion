package org.zerionproject.app.api.channel;

import org.zerionproject.core.api.db.DbException;

public class ChannelException extends DbException {

	public ChannelException() {
		super();
	}

	public ChannelException(Throwable cause) {
		super(cause);
	}
}
