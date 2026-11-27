package org.zerionproject.app.api.channel;

public class ChannelPostQuotaException extends ChannelRequestQueuedException {

	public ChannelPostQuotaException(long itemId) {
		super(false, itemId);
	}
}
