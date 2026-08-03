package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.client.BdfMessageContext;
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

import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_CONTENT_TYPE_BYTES;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_DISSOLVED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_EPOCH_COMMIT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ADDED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LEFT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_REMOVED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_MEMBER_ROLE_CHANGED;
import static org.zerionproject.app.messaging.MessageTypes.GROUP_POST;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.MESSAGE_REACTION;
import static org.zerionproject.app.messaging.MessageTypes.TYPING_INDICATOR;
import static org.zerionproject.app.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_CHUNK_COUNT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_CHUNK_INDEX;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ROOT_HASH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TOTAL_SIZE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_IS_TYPING;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REACTION_EMOJI;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_URL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_PREVIEW_IMAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TARGET_MESSAGE_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_EPOCH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_SENDER_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_CIPHERTEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_RECORD_SIG;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ADDED_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ADDED_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_REMOVED_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_LEAVING_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_FROM_EPOCH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_TO_EPOCH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_PQ_SEED;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_TARGET_PUBKEY;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_NEW_ROLE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_MEMBER_LIST;
import static org.zerionproject.app.messaging.MessagingConstants.SIGNING_LABEL_GROUP_POST;
import static org.zerionproject.app.messaging.MessagingConstants.SIGNING_LABEL_GROUP_MEMBERSHIP;
import static org.zerionproject.app.util.ValidationUtils.validateAutoDeleteTimer;

@Immutable
@NotNullByDefault
class PrivateMessageValidator implements MessageValidator {

	private final BdfReaderFactory bdfReaderFactory;
	private final MetadataEncoder metadataEncoder;
	private final Clock clock;
	private final org.zerionproject.core.api.crypto.CryptoComponent crypto;

	PrivateMessageValidator(BdfReaderFactory bdfReaderFactory,
			MetadataEncoder metadataEncoder, Clock clock,
			org.zerionproject.core.api.crypto.CryptoComponent crypto) {
		this.bdfReaderFactory = bdfReaderFactory;
		this.metadataEncoder = metadataEncoder;
		this.clock = clock;
		this.crypto = crypto;
	}

	@Override
	public MessageContext validateMessage(Message m, Group g)
			throws InvalidMessageException {
		BdfMessageContext context = validateToBdf(m, g);
		try {
			Metadata meta = metadataEncoder.encode(context.getDictionary());
			return new MessageContext(meta, context.getDependencies());
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
	}

	BdfMessageContext validateToBdf(Message m, Group g)
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
				} else if (messageType == MessageTypes.MESH_PREKEY_BUNDLE) {
					if (!reader.eof()) throw new FormatException();
					context = validateMeshPrekeyBundle(m, list);
				} else if (messageType == GROUP_POST) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupPost(m, list);
				} else if (messageType == GROUP_MEMBER_ADDED) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupMemberAdded(m, list);
				} else if (messageType == GROUP_MEMBER_REMOVED) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupMemberRemoved(m, list);
				} else if (messageType == GROUP_MEMBER_LEFT) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupMemberLeft(m, list);
				} else if (messageType == GROUP_DISSOLVED) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupDissolved(m, list);
				} else if (messageType == GROUP_EPOCH_COMMIT) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupEpochCommit(m, list);
				} else if (messageType == GROUP_MEMBER_ROLE_CHANGED) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupRoleChanged(m, list);
				} else if (messageType == GROUP_MEMBER_LIST_SNAPSHOT) {
					if (!reader.eof()) throw new FormatException();
					context = validateGroupMemberListSnapshot(m, list);
				} else if (messageType == MessageTypes.GROUPTR_INVITE_OFFER) {
					if (!reader.eof()) throw new FormatException();
					context = validateGrouptrInviteOffer(m, list);
				} else if (messageType == MessageTypes.GROUPTR_INVITE_ACCEPT) {
					if (!reader.eof()) throw new FormatException();
					context = validateGrouptrInviteResponse(m, list,
							MessageTypes.GROUPTR_INVITE_ACCEPT);
				} else if (messageType == MessageTypes.GROUPTR_INVITE_DECLINE) {
					if (!reader.eof()) throw new FormatException();
					context = validateGrouptrInviteResponse(m, list,
							MessageTypes.GROUPTR_INVITE_DECLINE);
				} else {
					throw new InvalidMessageException();
				}
			}
			return context;
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

	private BdfMessageContext validateMeshPrekeyBundle(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 2);
		body.getRaw(1);

		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, MessageTypes.MESH_PREKEY_BUNDLE);
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

	private BdfMessageContext validateGroupPost(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 7, 8);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		long epoch = body.getLong(2);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		byte[] senderPubKey = body.getRaw(3);
		checkLength(senderPubKey, 32);
		String senderName = body.getString(4);
		checkLength(senderName, 0, 256);
		byte[] ciphertext = body.getRaw(5);
		checkLength(ciphertext, 1, MAX_PRIVATE_MESSAGE_TEXT_LENGTH + 1024);
		byte[] recordSig = body.getRaw(6);
		checkLength(recordSig, 1, 4096);
		long autoDeleteTimer = NO_AUTO_DELETE_TIMER;
		if (body.size() == 8) {
			autoDeleteTimer = validateAutoDeleteTimer(body.getOptionalLong(7));
		}
		long ttlForSig =
				autoDeleteTimer == NO_AUTO_DELETE_TIMER ? 0L : autoDeleteTimer;
		byte[] signedInput = buildGroupPostSignedInput(
				groupId, (int) epoch, senderPubKey, senderName, ciphertext,
				m.getTimestamp(), ttlForSig);
		byte[] sigToVerify = recordSig;
		if (recordSig.length == org.zerionproject.core.api.crypto
				.PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
			sigToVerify = new byte[64];
			System.arraycopy(recordSig, 0, sigToVerify, 0, 64);
		}
		verifyOrThrow(sigToVerify, SIGNING_LABEL_GROUP_POST,
				signedInput, senderPubKey);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_POST);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_SENDER_PUBKEY, senderPubKey);
		meta.put("groupSenderName", senderName);
		meta.put(MSG_KEY_GROUP_CIPHERTEXT, ciphertext);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, recordSig);
		if (autoDeleteTimer != NO_AUTO_DELETE_TIMER) {
			meta.put(MSG_KEY_AUTO_DELETE_TIMER, autoDeleteTimer);
		}
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupMemberAdded(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 7);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		byte[] addedPubKey = body.getRaw(2);
		checkLength(addedPubKey, 32);
		String addedName = body.getString(3);
		checkLength(addedName, 1, 256);
		long epoch = body.getLong(4);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		long timestamp = body.getLong(5);
		byte[] sig = body.getRaw(6);
		checkLength(sig, 1, 4096);
		byte[] signedInput = membershipSignedInput(
				groupId, addedPubKey, (int) epoch, timestamp, (byte) 0x01);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_MEMBER_ADDED);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_ADDED_PUBKEY, addedPubKey);
		meta.put(MSG_KEY_GROUP_ADDED_NAME, addedName);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupMemberRemoved(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 7);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		byte[] removedPubKey = body.getRaw(2);
		checkLength(removedPubKey, 32);
		long fromEpoch = body.getLong(3);
		long toEpoch = body.getLong(4);
		if (fromEpoch < 0L || toEpoch < 0L
				|| fromEpoch > 0xFFFFFFFFL || toEpoch > 0xFFFFFFFFL
				|| toEpoch != fromEpoch + 1) {
			throw new FormatException();
		}
		long timestamp = body.getLong(5);
		byte[] sig = body.getRaw(6);
		checkLength(sig, 1, 4096);
		byte[] signedInput = removedSignedInput(groupId, removedPubKey,
				(int) fromEpoch, (int) toEpoch, timestamp);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_MEMBER_REMOVED);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_REMOVED_PUBKEY, removedPubKey);
		meta.put(MSG_KEY_GROUP_FROM_EPOCH, fromEpoch);
		meta.put(MSG_KEY_GROUP_TO_EPOCH, toEpoch);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupMemberLeft(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 6);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		byte[] leavingPubKey = body.getRaw(2);
		checkLength(leavingPubKey, 32);
		long epoch = body.getLong(3);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		long timestamp = body.getLong(4);
		byte[] sig = body.getRaw(5);
		checkLength(sig, 1, 4096);
		byte[] signedInput = membershipSignedInput(
				groupId, leavingPubKey, (int) epoch, timestamp, (byte) 0x03);
		byte[] sigToVerify = sig;
		if (sig.length == org.zerionproject.core.api.crypto
				.PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
			sigToVerify = new byte[64];
			System.arraycopy(sig, 0, sigToVerify, 0, 64);
		}
		verifyOrThrow(sigToVerify, SIGNING_LABEL_GROUP_MEMBERSHIP,
				signedInput, leavingPubKey);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_MEMBER_LEFT);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_LEAVING_PUBKEY, leavingPubKey);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupDissolved(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 5);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		long epoch = body.getLong(2);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		long timestamp = body.getLong(3);
		byte[] sig = body.getRaw(4);
		checkLength(sig, 1, 4096);
		byte[] signedInput = dissolveSignedInput(
				groupId, (int) epoch, timestamp);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_DISSOLVED);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupEpochCommit(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 6);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		long fromEpoch = body.getLong(2);
		long toEpoch = body.getLong(3);
		if (fromEpoch < 0L || toEpoch < 0L
				|| fromEpoch > 0xFFFFFFFFL || toEpoch > 0xFFFFFFFFL
				|| toEpoch != fromEpoch + 1) {
			throw new FormatException();
		}
		byte[] pqSeed = body.getRaw(4);
		checkLength(pqSeed, 1, 4096);
		byte[] sig = body.getRaw(5);
		checkLength(sig, 1, 4096);
		byte[] signedInput = epochCommitSignedInput(
				groupId, (int) fromEpoch, (int) toEpoch, pqSeed,
				m.getTimestamp());
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_EPOCH_COMMIT);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_FROM_EPOCH, fromEpoch);
		meta.put(MSG_KEY_GROUP_TO_EPOCH, toEpoch);
		meta.put(MSG_KEY_GROUP_PQ_SEED, pqSeed);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupEpochCommitSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGrouptrInviteOffer(Message m,
			BdfList body) throws FormatException {
		checkSize(body, 8);
		byte[] grouptrGroupId = body.getRaw(1);
		checkLength(grouptrGroupId, 32);
		String groupName = body.getString(2);
		checkLength(groupName, 0, 100);
		byte[] salt = body.getRaw(3);
		checkLength(salt, 32);
		String creatorName = body.getString(4);
		checkLength(creatorName, 1,
				org.zerionproject.core.api.identity.AuthorConstants
						.MAX_AUTHOR_NAME_LENGTH);
		byte[] creatorPubKey = body.getRaw(5);
		checkLength(creatorPubKey, 32);
		long timestamp = body.getLong(6);
		byte[] sig = body.getRaw(7);
		checkLength(sig, 1, 4096);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_MSG_TYPE,
				(long) MessageTypes.GROUPTR_INVITE_OFFER);
		meta.put(MSG_KEY_GROUP_ID, grouptrGroupId);
		meta.put(MessagingConstants.MSG_KEY_GTR_INVITE_NAME, groupName);
		meta.put(MessagingConstants.MSG_KEY_GTR_INVITE_SALT, salt);
		meta.put(MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_NAME,
				creatorName);
		meta.put(MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_PUB,
				creatorPubKey);
		meta.put("gtrInviteTimestamp", timestamp);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGrouptrInviteResponse(Message m,
			BdfList body, int kind) throws FormatException {
		checkSize(body, 4);
		byte[] grouptrGroupId = body.getRaw(1);
		checkLength(grouptrGroupId, 32);
		long timestamp = body.getLong(2);
		byte[] sig = body.getRaw(3);
		checkLength(sig, 1, 4096);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, (long) kind);
		meta.put(MSG_KEY_GROUP_ID, grouptrGroupId);
		meta.put("gtrInviteTimestamp", timestamp);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		return new BdfMessageContext(meta);
	}

	private void verifyOrThrow(byte[] sig, String label, byte[] signed,
			byte[] pubKey) throws FormatException {
		org.zerionproject.core.api.crypto.PublicKey p =
				new org.zerionproject.core.api.crypto.SignaturePublicKey(
						pubKey);
		try {
			boolean ok = crypto.verifySignature(sig, label, signed, p);
			if (!ok) throw new FormatException();
		} catch (java.security.GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private byte[] buildGroupPostSignedInput(byte[] groupId, int epoch,
			byte[] senderPubKey, String senderName, byte[] ciphertext,
			long timestamp, long ttlMs) {
		byte[] nameHash = crypto.hash(
				"org.zerionproject/GROUP_POST_NAME",
				senderName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		byte[] ctHash = crypto.hash(
				"org.zerionproject/GROUP_POST_CT", ciphertext);
		byte[] out = new byte[32 + 4 + 32 + nameHash.length
				+ ctHash.length + 8 + 8];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(senderPubKey, 0, out, 36, 32);
		System.arraycopy(nameHash, 0, out, 68, nameHash.length);
		int off = 68 + nameHash.length;
		System.arraycopy(ctHash, 0, out, off, ctHash.length);
		off += ctHash.length;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		off += 8;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (ttlMs >>> ((7 - i) * 8));
		}
		return out;
	}

	private byte[] membershipSignedInput(byte[] groupId, byte[] targetPubKey,
			int epoch, long timestamp, byte action) {
		byte[] out = new byte[32 + 32 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(targetPubKey, 0, out, 32, 32);
		for (int i = 0; i < 4; i++) {
			out[64 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[68 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[76] = action;
		return out;
	}

	private byte[] removedSignedInput(byte[] groupId, byte[] removedPubKey,
			int fromEpoch, int toEpoch, long timestamp) {
		byte[] out = new byte[32 + 32 + 4 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(removedPubKey, 0, out, 32, 32);
		for (int i = 0; i < 4; i++) {
			out[64 + i] = (byte) (fromEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 4; i++) {
			out[68 + i] = (byte) (toEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[72 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[80] = (byte) 0x02;
		return out;
	}

	private byte[] dissolveSignedInput(byte[] groupId, int epoch,
			long timestamp) {
		byte[] out = new byte[32 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[36 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[44] = (byte) 0x04;
		return out;
	}

	private BdfMessageContext validateGroupRoleChanged(Message m,
			BdfList body) throws FormatException {
		checkSize(body, 7);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		byte[] targetPubKey = body.getRaw(2);
		checkLength(targetPubKey, 32);
		long newRole = body.getLong(3);
		if (newRole < 0L || newRole > 2L) throw new FormatException();
		long epoch = body.getLong(4);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		long timestamp = body.getLong(5);
		byte[] sig = body.getRaw(6);
		checkLength(sig, 1, 4096);
		byte[] signedInput = roleChangedSignedInput(groupId, targetPubKey,
				(int) newRole, (int) epoch, timestamp);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_MEMBER_ROLE_CHANGED);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_TARGET_PUBKEY, targetPubKey);
		meta.put(MSG_KEY_GROUP_NEW_ROLE, newRole);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private byte[] roleChangedSignedInput(byte[] groupId,
			byte[] targetPubKey, int newRole, int epoch, long timestamp) {
		byte[] out = new byte[32 + 32 + 1 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(targetPubKey, 0, out, 32, 32);
		out[64] = (byte) newRole;
		for (int i = 0; i < 4; i++) {
			out[65 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[69 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[77] = (byte) 0x06;
		return out;
	}

	private BdfMessageContext validateGroupMemberListSnapshot(Message m,
			BdfList body) throws FormatException {
		checkSize(body, 6);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		long epoch = body.getLong(2);
		if (epoch < 0L || epoch > 0xFFFFFFFFL) throw new FormatException();
		long timestamp = body.getLong(3);
		BdfList memberList = body.getList(4);
		checkSize(memberList, 0, 256);
		byte[] memberCanonical =
				new byte[memberList.size() * (32 + 1 + 4)];
		int off = 0;
		for (int i = 0; i < memberList.size(); i++) {
			BdfList m2 = memberList.getList(i);
			checkSize(m2, 5);
			byte[] pk = m2.getRaw(0);
			checkLength(pk, 32);
			String name = m2.getString(1);
			checkLength(name, 0, 256);
			long joinedAt = m2.getLong(2);
			if (joinedAt < 0L) throw new FormatException();
			long joinedAtEpoch = m2.getLong(3);
			if (joinedAtEpoch < 0L || joinedAtEpoch > 0xFFFFFFFFL) {
				throw new FormatException();
			}
			long role = m2.getLong(4);
			if (role < 0L || role > 2L) throw new FormatException();
			System.arraycopy(pk, 0, memberCanonical, off, 32);
			memberCanonical[off + 32] = (byte) role;
			int je = (int) joinedAtEpoch;
			for (int j = 0; j < 4; j++) {
				memberCanonical[off + 33 + j] =
						(byte) (je >>> ((3 - j) * 8));
			}
			off += 37;
		}
		byte[] sig = body.getRaw(5);
		checkLength(sig, 1, 4096);
		byte[] signedInput = snapshotSignedInput(groupId, (int) epoch,
				timestamp, memberCanonical);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_MSG_TYPE, GROUP_MEMBER_LIST_SNAPSHOT);
		meta.put(MSG_KEY_GROUP_ID, groupId);
		meta.put(MSG_KEY_GROUP_EPOCH, epoch);
		meta.put(MSG_KEY_GROUP_MEMBER_LIST, memberCanonical);
		meta.put(MSG_KEY_GROUP_RECORD_SIG, sig);
		meta.put("groupMembershipSignedInput", signedInput);
		return new BdfMessageContext(meta);
	}

	private byte[] snapshotSignedInput(byte[] groupId, int epoch,
			long timestamp, byte[] memberCanonical) {
		byte[] mlHash = crypto.hash(
				"org.zerionproject/GROUP_MEMBER_LIST",
				memberCanonical);
		byte[] out = new byte[32 + 4 + 8 + mlHash.length + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[36 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		System.arraycopy(mlHash, 0, out, 44, mlHash.length);
		out[44 + mlHash.length] = (byte) 0x07;
		return out;
	}

	private byte[] epochCommitSignedInput(byte[] groupId, int fromEpoch,
			int toEpoch, byte[] pqSeed, long timestamp) {
		byte[] seedHash = crypto.hash(
				"org.zerionproject/GROUP_EPOCH_SEED", pqSeed);
		byte[] out = new byte[32 + 4 + 4 + seedHash.length + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (fromEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 4; i++) {
			out[36 + i] = (byte) (toEpoch >>> ((3 - i) * 8));
		}
		System.arraycopy(seedHash, 0, out, 40, seedHash.length);
		int off = 40 + seedHash.length;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[off + 8] = (byte) 0x05;
		return out;
	}
}
