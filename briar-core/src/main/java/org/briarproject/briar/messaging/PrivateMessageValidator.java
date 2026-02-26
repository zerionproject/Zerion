package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.sync.validation.MessageValidator;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.attachment.CountingInputStream;
import org.briarproject.nullsafety.NotNullByDefault;

import org.briarproject.bramble.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.briarproject.briar.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.briarproject.briar.messaging.MessageTypes.MESSAGE_REACTION;
import static org.briarproject.briar.messaging.MessageTypes.TYPING_INDICATOR;
import static org.briarproject.briar.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.briarproject.briar.messaging.MessageTypes.VOICE_SIGNAL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_CHUNK_COUNT;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_CHUNK_INDEX;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_ROOT_HASH;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TOTAL_SIZE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_IS_TYPING;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_REACTION_EMOJI;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_URL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_HAS_PREVIEW_IMAGE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TARGET_MESSAGE_ID;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.util.ValidationUtils.validateAutoDeleteTimer;

@Immutable
@NotNullByDefault
class PrivateMessageValidator implements MessageValidator {

	private final BdfReaderFactory bdfReaderFactory;
	private final MetadataEncoder metadataEncoder;
	private final Clock clock;

	PrivateMessageValidator(BdfReaderFactory bdfReaderFactory,
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
			BdfReader reader = bdfReaderFactory.createReader(countIn,
					BdfReader.DEFAULT_NESTED_LIMIT,
					MAX_MESSAGE_BODY_LENGTH, true);
			BdfList list = reader.readList();
			long bytesRead = countIn.getBytesRead();
			BdfMessageContext context;
			if (list.size() == 1) {
				if (!reader.eof()) throw new FormatException();
				context = validateLegacyPrivateMessage(m, list);
			} else {
				int messageType = list.getInt(0);
				if (messageType == PRIVATE_MESSAGE) {
					if (!reader.eof()) throw new FormatException();
					context = validatePrivateMessage(m, list);
				} else if (messageType == ATTACHMENT) {
					context = validateAttachment(m, list, bytesRead);
				} else if (messageType == VOICE_SIGNAL) {
					if (!reader.eof()) throw new FormatException();
					context = validateVoiceSignal(m, list);
				} else if (messageType == ATTACHMENT_MANIFEST) {
					if (!reader.eof()) throw new FormatException();
					context = validateAttachmentManifest(m, list);
				} else if (messageType == ATTACHMENT_CHUNK) {
					context = validateAttachmentChunk(m, list, bytesRead);
				} else if (messageType == MESSAGE_REACTION) {
					if (!reader.eof()) throw new FormatException();
					context = validateMessageReaction(m, list);
				} else if (messageType == TYPING_INDICATOR) {
					if (!reader.eof()) throw new FormatException();
					context = validateTypingIndicator(m, list);
				} else if (messageType == LINK_PREVIEW_MESSAGE) {
					if (!reader.eof()) throw new FormatException();
					context = validateLinkPreviewMessage(m, list);
				} else {
					throw new InvalidMessageException();
				}
			}
			Metadata meta = metadataEncoder.encode(context.getDictionary());
			return new MessageContext(meta, context.getDependencies());
		} catch (IOException e) {
			throw new InvalidMessageException(e);
		}
	}

	private BdfMessageContext validateLegacyPrivateMessage(Message m,
			BdfList body) throws FormatException {
		checkSize(body, 1);
		String text = body.getString(0);
		checkLength(text, 0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validatePrivateMessage(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 3, 5);
		String text = body.getOptionalString(1);
		checkLength(text, 0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		BdfList headers = body.getList(2);
		if (text == null) checkSize(headers, 1, MAX_ATTACHMENTS_PER_MESSAGE);
		else checkSize(headers, 0, MAX_ATTACHMENTS_PER_MESSAGE);
		Collection<MessageId> dependencies = new ArrayList<>();
		for (int i = 0; i < headers.size(); i++) {
			BdfList header = headers.getList(i);
			checkSize(header, 2);
			byte[] id = header.getRaw(0);
			checkLength(id, UniqueId.LENGTH);
			String contentType = header.getString(1);
			checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
			dependencies.add(new MessageId(id));
		}

		long timer = NO_AUTO_DELETE_TIMER;
		if (body.size() >= 4) {
			timer = validateAutoDeleteTimer(body.getOptionalLong(3));
		}
		byte[] replyToId = null;
		if (body.size() == 5) {
			replyToId = body.getOptionalRaw(4);
			if (replyToId != null) checkLength(replyToId, UniqueId.LENGTH);
		}
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
		meta.put(MSG_KEY_HAS_TEXT, text != null);
		meta.put(MSG_KEY_ATTACHMENT_HEADERS, headers);
		if (timer != NO_AUTO_DELETE_TIMER) {
			meta.put(MSG_KEY_AUTO_DELETE_TIMER, timer);
		}
		if (replyToId != null) {
			meta.put(MSG_KEY_REPLY_TO_ID, replyToId);
		}
		if (dependencies.isEmpty()) {
			return new BdfMessageContext(meta);
		}
		return new BdfMessageContext(meta, dependencies);
	}

	private BdfMessageContext validateAttachment(Message m, BdfList descriptor,
			long descriptorLength) throws FormatException {
		checkSize(descriptor, 2);
		String contentType = descriptor.getString(1);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, (int) descriptorLength);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateVoiceSignal(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 3, 5);
		int signalType = body.getInt(1);
		if (signalType < 0 || signalType > 9) {
			throw new FormatException();
		}
		String callId = body.getString(2);
		checkLength(callId, 1, 64);
		if (body.size() > 3) {
			String payload = body.getOptionalString(3);
			if (payload != null) {
				checkLength(payload, 0, 16384);
			}
		}
		if (body.size() > 4) {
			Long durationMs = body.getOptionalLong(4);
			if (durationMs != null && durationMs < 0) {
				throw new FormatException();
			}
		}
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, VOICE_SIGNAL);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateAttachmentManifest(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 6);

		String contentType = body.getString(1);
		checkLength(contentType, 1, MAX_CONTENT_TYPE_BYTES);

		long totalSize = body.getLong(2);
		if (totalSize <= 0 || totalSize > 10 * 1024 * 1024) {
			throw new FormatException();
		}

		int chunkCount = body.getInt(3);
		if (chunkCount <= 0 || chunkCount > 100) {
			throw new FormatException();
		}

		byte[] rootHash = body.getRaw(4);
		checkLength(rootHash, 32);

		BdfList chunkIds = body.getList(5);
		if (chunkIds.size() != chunkCount) {
			throw new FormatException();
		}
		Collection<MessageId> dependencies = new ArrayList<>(chunkCount);
		for (int i = 0; i < chunkIds.size(); i++) {
			byte[] chunkId = chunkIds.getRaw(i);
			checkLength(chunkId, UniqueId.LENGTH);
			dependencies.add(new MessageId(chunkId));
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT_MANIFEST);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_TOTAL_SIZE, totalSize);
		meta.put(MSG_KEY_CHUNK_COUNT, chunkCount);
		meta.put(MSG_KEY_ROOT_HASH, rootHash);
		return new BdfMessageContext(meta, dependencies);
	}

	private BdfMessageContext validateAttachmentChunk(Message m, BdfList header,
			long headerLength) throws FormatException {
		checkSize(header, 3);

		int chunkIndex = header.getInt(1);
		if (chunkIndex < 0 || chunkIndex >= 100) {
			throw new FormatException();
		}

		int chunkDataLength = header.getInt(2);
		if (chunkDataLength <= 0 || chunkDataLength > 512 * 1024) {
			throw new FormatException();
		}

		int expectedBodyLength = (int) headerLength + chunkDataLength;
		if (m.getBody().length != expectedBodyLength) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT_CHUNK);
		meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, (int) headerLength);
		return new BdfMessageContext(meta);
	}

	private static final java.util.Set<String> ALLOWED_REACTION_EMOJIS =
			new java.util.HashSet<>(java.util.Arrays.asList(
					"\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02",
					"\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21",
					"thumbsup", "heart", "laugh",
					"surprise", "sad", "angry"));

	private BdfMessageContext validateMessageReaction(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 3);
		byte[] targetId = body.getRaw(1);
		checkLength(targetId, UniqueId.LENGTH);
		String emoji = body.getString(2);
		checkLength(emoji, 1, 64);
		if (!ALLOWED_REACTION_EMOJIS.contains(emoji)) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, MESSAGE_REACTION);
		meta.put(MSG_KEY_TARGET_MESSAGE_ID, targetId);
		meta.put(MSG_KEY_REACTION_EMOJI, emoji);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateTypingIndicator(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 2);
		boolean isTyping = body.getBoolean(1);

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, TYPING_INDICATOR);
		meta.put(MSG_KEY_IS_TYPING, isTyping);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateLinkPreviewMessage(Message m,
			BdfList body) throws FormatException {
		checkSize(body, 5, 6);
		String text = body.getOptionalString(1);
		if (text != null) {
			checkLength(text, 0, MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		}
		String previewUrl = body.getString(2);
		checkLength(previewUrl, 1, 2048);
		String previewTitle = body.getString(3);
		checkLength(previewTitle, 1, 512);
		String previewDescription = body.getOptionalString(4);
		if (previewDescription != null) {
			checkLength(previewDescription, 0, 1024);
		}
		boolean hasImage = body.size() == 6 && body.getRaw(5) != null;

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE, LINK_PREVIEW_MESSAGE);
		meta.put(MSG_KEY_HAS_TEXT, text != null);
		meta.put(MSG_KEY_PREVIEW_URL, previewUrl);
		meta.put(MSG_KEY_PREVIEW_TITLE, previewTitle);
		if (previewDescription != null) {
			meta.put(MSG_KEY_PREVIEW_DESCRIPTION, previewDescription);
		}
		meta.put(MSG_KEY_HAS_PREVIEW_IMAGE, hasImage);
		return new BdfMessageContext(meta);
	}
}
