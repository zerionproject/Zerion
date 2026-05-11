package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.client.BdfMessageValidator;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_SIGNATURE_LENGTH;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_JOIN;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.privategroup.MessageType.SENDER_KEYS_POST;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory.SIGNING_LABEL_INVITE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_CIPHERTEXT;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_EPOCH;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_INITIAL_JOIN_MSG;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MEMBER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MESSAGE_INDEX;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_NONCE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PREVIOUS_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_READ;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_SIGNATURE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TIMESTAMP;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_TYPE;

@Immutable
@NotNullByDefault
class GroupMessageValidator extends BdfMessageValidator {

	private static final int NONCE_SIZE = 12;
	private static final int TAG_SIZE = 16;

	private final PrivateGroupFactory privateGroupFactory;
	private final GroupInvitationFactory groupInvitationFactory;
	private final CryptoComponent crypto;

	GroupMessageValidator(PrivateGroupFactory privateGroupFactory,
			ClientHelper clientHelper, MetadataEncoder metadataEncoder,
			Clock clock, GroupInvitationFactory groupInvitationFactory,
			CryptoComponent crypto) {
		super(clientHelper, metadataEncoder, clock);
		this.privateGroupFactory = privateGroupFactory;
		this.groupInvitationFactory = groupInvitationFactory;
		this.crypto = crypto;
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws InvalidMessageException, FormatException {

		checkSize(body, 4, 9);
		int type = body.getInt(0);
		BdfList memberList = body.getList(1);
		Author member = clientHelper.parseAndValidateAuthor(memberList);

		BdfMessageContext c;
		if (type == JOIN.getInt()) {
			c = validateJoin(m, g, body, member);
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else if (type == POST.getInt()) {
			c = validatePost(m, g, body, member);
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else if (type == SENDER_KEYS_POST.getInt()) {
			c = validateSenderKeysPost(m, g, body, member);
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else {
			throw new InvalidMessageException("Unknown Message Type");
		}
		c.getDictionary().put(KEY_TYPE, type);
		return c;
	}

	private BdfMessageContext validateJoin(Message m, Group g, BdfList body,
			Author member) throws FormatException {
		checkSize(body, 4);
		BdfList inviteList = body.getOptionalList(2);
		byte[] memberSignature = body.getRaw(3);
		checkLength(memberSignature, 1, MAX_SIGNATURE_LENGTH);
		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		Author creator = pg.getCreator();
		boolean isCreator = member.equals(creator);
		if (isCreator) {
			if (inviteList != null) throw new FormatException();
		} else {
			if (inviteList == null) throw new FormatException();
			checkSize(inviteList, 2);
			long inviteTimestamp = inviteList.getLong(0);
			if (m.getTimestamp() <= inviteTimestamp)
				throw new FormatException();
			byte[] creatorSignature = inviteList.getRaw(1);
			checkLength(creatorSignature, 1, MAX_SIGNATURE_LENGTH);
			BdfList token = groupInvitationFactory.createInviteToken(
					creator.getId(), member.getId(), g.getId(),
					inviteTimestamp);
			try {
				clientHelper.verifySignature(creatorSignature,
						SIGNING_LABEL_INVITE,
						token, creator.getPublicKey());
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}
		}
		BdfList memberList = body.getList(1);
		BdfList signed = BdfList.of(
				g.getId(),
				m.getTimestamp(),
				memberList,
				inviteList
		);
		try {
			clientHelper.verifySignature(memberSignature, SIGNING_LABEL_JOIN,
					signed, member.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_INITIAL_JOIN_MSG, isCreator);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validatePost(Message m, Group g, BdfList body,
			Author member) throws FormatException {
		checkSize(body, 6);
		byte[] parentId = body.getOptionalRaw(2);
		checkLength(parentId, MessageId.LENGTH);
		byte[] previousMessageId = body.getRaw(3);
		checkLength(previousMessageId, MessageId.LENGTH);
		String text = body.getString(4);
		checkLength(text, 1, MAX_GROUP_POST_TEXT_LENGTH);
		byte[] signature = body.getRaw(5);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);
		BdfList memberList = body.getList(1);
		BdfList signed = BdfList.of(
				g.getId(),
				m.getTimestamp(),
				memberList,
				parentId,
				previousMessageId,
				text
		);
		try {
			clientHelper.verifySignature(signature, SIGNING_LABEL_POST,
					signed, member.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		Collection<MessageId> dependencies = new ArrayList<>();
		if (parentId != null) dependencies.add(new MessageId(parentId));
		dependencies.add(new MessageId(previousMessageId));
		BdfDictionary meta = new BdfDictionary();
		if (parentId != null) meta.put(KEY_PARENT_MSG_ID, parentId);
		meta.put(KEY_PREVIOUS_MSG_ID, previousMessageId);
		return new BdfMessageContext(meta, dependencies);
	}

	private BdfMessageContext validateSenderKeysPost(Message m, Group g,
			BdfList body, Author member) throws FormatException {
		checkSize(body, 9);
		byte[] parentId = body.getOptionalRaw(2);
		checkLength(parentId, MessageId.LENGTH);
		byte[] previousMessageId = body.getRaw(3);
		checkLength(previousMessageId, MessageId.LENGTH);
		byte[] ciphertext = body.getRaw(4);
		int maxCiphertextLength = MAX_GROUP_POST_TEXT_LENGTH + TAG_SIZE;
		checkLength(ciphertext, TAG_SIZE + 1, maxCiphertextLength);
		byte[] nonce = body.getRaw(5);
		checkLength(nonce, NONCE_SIZE, NONCE_SIZE);
		int epoch = body.getInt(6);
		if (epoch < 0) throw new FormatException();
		int messageIndex = body.getInt(7);
		if (messageIndex < 0) throw new FormatException();
		byte[] signature = body.getRaw(8);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		byte[] signatureInput = buildSignatureInput(ciphertext, nonce,
				g.getId().getBytes());

		try {
			crypto.verifySignature(signature,
					GroupMessageCrypto.MESSAGE_SIGNATURE_LABEL,
					signatureInput, member.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		Collection<MessageId> dependencies = new ArrayList<>();
		if (parentId != null) dependencies.add(new MessageId(parentId));
		dependencies.add(new MessageId(previousMessageId));

		BdfDictionary meta = new BdfDictionary();
		if (parentId != null) meta.put(KEY_PARENT_MSG_ID, parentId);
		meta.put(KEY_PREVIOUS_MSG_ID, previousMessageId);
		meta.put(KEY_CIPHERTEXT, ciphertext);
		meta.put(KEY_NONCE, nonce);
		meta.put(KEY_EPOCH, epoch);
		meta.put(KEY_MESSAGE_INDEX, messageIndex);
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta, dependencies);
	}

	private byte[] buildSignatureInput(byte[] ciphertext, byte[] nonce,
			byte[] groupId) {
		byte[] input = new byte[ciphertext.length + nonce.length + groupId.length];
		int offset = 0;
		System.arraycopy(ciphertext, 0, input, offset, ciphertext.length);
		offset += ciphertext.length;
		System.arraycopy(nonce, 0, input, offset, nonce.length);
		offset += nonce.length;
		System.arraycopy(groupId, 0, input, offset, groupId.length);
		return input;
	}

	private void addMessageMetadata(BdfMessageContext c, BdfList member,
			long timestamp) {
		c.getDictionary().put(KEY_MEMBER, member);
		c.getDictionary().put(KEY_TIMESTAMP, timestamp);
		c.getDictionary().put(KEY_READ, false);
	}
}
