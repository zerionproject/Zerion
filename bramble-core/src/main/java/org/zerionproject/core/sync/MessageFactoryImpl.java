package org.zerionproject.core.sync;

import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.sync.Message.FORMAT_VERSION;
import static org.zerionproject.core.api.sync.MessageId.BLOCK_LABEL;
import static org.zerionproject.core.api.sync.MessageId.ID_LABEL;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.util.ByteUtils.INT_64_BYTES;

@Immutable
@NotNullByDefault
class MessageFactoryImpl implements MessageFactory {

	private static final byte[] FORMAT_VERSION_BYTES =
			new byte[] {FORMAT_VERSION};

	private final CryptoComponent crypto;

	@Inject
	MessageFactoryImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public Message createMessage(GroupId g, long timestamp, byte[] body) {
		if (body.length == 0) throw new IllegalArgumentException();
		if (body.length > MAX_MESSAGE_BODY_LENGTH)
			throw new IllegalArgumentException();
		MessageId id = getMessageId(g, timestamp, body);
		return new Message(id, g, timestamp, body);
	}

	private MessageId getMessageId(GroupId g, long timestamp, byte[] body) {
		byte[] rootHash = crypto.hash(BLOCK_LABEL, FORMAT_VERSION_BYTES, body);
		byte[] timeBytes = new byte[INT_64_BYTES];
		ByteUtils.writeUint64(timestamp, timeBytes, 0);
		byte[] idHash = crypto.hash(ID_LABEL, FORMAT_VERSION_BYTES,
				g.getBytes(), timeBytes, rootHash);
		return new MessageId(idHash);
	}

	@Override
	public Message createMessage(byte[] raw) {
		if (raw.length <= MESSAGE_HEADER_LENGTH)
			throw new IllegalArgumentException();
		if (raw.length > MAX_MESSAGE_LENGTH)
			throw new IllegalArgumentException();
		byte[] groupId = new byte[UniqueId.LENGTH];
		System.arraycopy(raw, 0, groupId, 0, UniqueId.LENGTH);
		GroupId g = new GroupId(groupId);
		long timestamp = ByteUtils.readUint64(raw, UniqueId.LENGTH);
		byte[] body = new byte[raw.length - MESSAGE_HEADER_LENGTH];
		System.arraycopy(raw, MESSAGE_HEADER_LENGTH, body, 0, body.length);
		MessageId id = getMessageId(g, timestamp, body);
		return new Message(id, g, timestamp, body);
	}

	@Override
	public byte[] getRawMessage(Message m) {
		byte[] body = m.getBody();
		byte[] raw = new byte[MESSAGE_HEADER_LENGTH + body.length];
		System.arraycopy(m.getGroupId().getBytes(), 0, raw, 0, UniqueId.LENGTH);
		ByteUtils.writeUint64(m.getTimestamp(), raw, UniqueId.LENGTH);
		System.arraycopy(body, 0, raw, MESSAGE_HEADER_LENGTH, body.length);
		return raw;
	}
}
