package org.zerionproject.app.api.channel;

import org.zerionproject.core.api.db.DbException;

public class ChannelSentNotSavedException extends DbException {

	private final long itemId;

	public ChannelSentNotSavedException(long itemId) {
		this.itemId = itemId;
	}

	public long getItemId() {
		return itemId;
	}
}
