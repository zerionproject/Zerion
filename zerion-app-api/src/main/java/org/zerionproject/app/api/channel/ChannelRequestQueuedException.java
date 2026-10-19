package org.zerionproject.app.api.channel;

import org.zerionproject.core.api.db.DbException;

public class ChannelRequestQueuedException extends DbException {

	private final boolean behindEarlierItems;
	private final long itemId;

	public ChannelRequestQueuedException() {
		this(false);
	}

	public ChannelRequestQueuedException(boolean behindEarlierItems) {
		this(behindEarlierItems, -1L);
	}

	public ChannelRequestQueuedException(boolean behindEarlierItems,
			long itemId) {
		this.behindEarlierItems = behindEarlierItems;
		this.itemId = itemId;
	}

	public boolean isBehindEarlierItems() {
		return behindEarlierItems;
	}

	public long getItemId() {
		return itemId;
	}
}
