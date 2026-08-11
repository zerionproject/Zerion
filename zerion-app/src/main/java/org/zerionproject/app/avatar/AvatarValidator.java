package org.zerionproject.app.avatar;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageContext;
import org.zerionproject.core.api.sync.validation.MessageValidator;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.app.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.zerionproject.app.avatar.AvatarConstants.MSG_KEY_VERSION;
import static org.zerionproject.app.avatar.AvatarConstants.MSG_TYPE_UPDATE;

@Immutable
@NotNullByDefault
class AvatarValidator implements MessageValidator {

	private final BdfReaderFactory bdfReaderFactory;
	private final MetadataEncoder metadataEncoder;
	private final Clock clock;

	AvatarValidator(BdfReaderFactory bdfReaderFactory,
			MetadataEncoder metadataEncoder, Clock clock) {
		this.bdfReaderFactory = bdfReaderFactory;
		this.metadataEncoder = metadataEncoder;
		this.clock = clock;
	}

	@Override
	public MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException {
		long now = clock.currentTimeMillis();
		if (m.getTimestamp() - now > MAX_CLOCK_DIFFERENCE) {
			throw new InvalidMessageException(
					"Timestamp is too far in the future");
		}
		try {
			InputStream in = new ByteArrayInputStream(m.getBody());
			CountingInputStream countIn =
					new CountingInputStream(in, MAX_MESSAGE_BODY_LENGTH);
			BdfReader reader = bdfReaderFactory.createReader(countIn);
			BdfList list = reader.readList();
			long bytesRead = countIn.getBytesRead();
			BdfDictionary d = validateUpdate(list, bytesRead);
			Metadata meta = metadataEncoder.encode(d);
			return new MessageContext(meta);
		} catch (IOException e) {
			throw new InvalidMessageException(e);
		}
	}

	private BdfDictionary validateUpdate(BdfList body, long descriptorLength)
			throws FormatException {
		checkSize(body, 3);
		int messageType = body.getInt(0);
		if (messageType != MSG_TYPE_UPDATE) throw new FormatException();
		long version = body.getLong(1);
		if (version < 0) throw new FormatException();
		String contentType = body.getString(2);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_VERSION, version);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, (int) descriptorLength);
		return meta;
	}

}
