package org.zerionproject.core.test;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;

@NotNullByDefault
public class TestMessageFactory implements MessageFactory {

	@Override
	public Message createMessage(GroupId g, long timestamp, byte[] body) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Message createMessage(byte[] raw) {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] getRawMessage(Message m) {
		byte[] body = m.getBody();
		byte[] raw = new byte[MESSAGE_HEADER_LENGTH + body.length];
		System.arraycopy(body, 0, raw, MESSAGE_HEADER_LENGTH, body.length);
		return raw;
	}
}
