package org.zerionproject.app.api.channel;

import org.zerionproject.core.api.db.DbException;

public class ChannelRequestQueuedException extends DbException {

	private final boolean behindEarlierItems;

	public ChannelRequestQueuedException() {
		this(false);
	}

	public ChannelRequestQueuedException(boolean behindEarlierItems) {
		this.behindEarlierItems = behindEarlierItems;
	}

	public boolean isBehindEarlierItems() {
		return behindEarlierItems;
	}
}
