package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.client.BdfMessageValidator;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Collections;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.GROUP_SALT_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_INVITATION_TEXT_LENGTH;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_NAME_LENGTH;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory.SIGNING_LABEL_INVITE;
import static org.briarproject.briar.privategroup.invitation.MessageType.ABORT;
import static org.briarproject.briar.privategroup.invitation.MessageType.INVITE;
import static org.briarproject.briar.privategroup.invitation.MessageType.JOIN;
import static org.briarproject.briar.privategroup.invitation.MessageType.LEAVE;
import static org.briarproject.briar.util.ValidationUtils.validateAutoDeleteTimer;

@Immutable
@NotNullByDefault
class GroupInvitationValidator extends BdfMessageValidator {

	private final PrivateGroupFactory privateGroupFactory;
	private final MessageEncoder messageEncoder;

	GroupInvitationValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock,
			PrivateGroupFactory privateGroupFactory,
			MessageEncoder messageEncoder) {
		super(clientHelper, metadataEncoder, clock);
		this.privateGroupFactory = privateGroupFactory;
		this.messageEncoder = messageEncoder;
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		MessageType type = MessageType.fromValue(body.getInt(0));
		switch (type) {
			case INVITE:
				return validateInviteMessage(m, body);
			case JOIN:
				return validateJoinMessage(m, body);
			case LEAVE:
				return validateLeaveMessage(m, body);
			case ABORT:
				return validateAbortMessage(m, body);
			default:
				throw new FormatException();
		}
	}

	private BdfMessageContext validateInviteMessage(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 6, 7);
		BdfList creatorList = body.getList(1);
		String groupName = body.getString(2);
		checkLength(groupName, 1, MAX_GROUP_NAME_LENGTH);
		byte[] salt = body.getRaw(3);
		checkLength(salt, GROUP_SALT_LENGTH);
		String text = body.getOptionalString(4);
		checkLength(text, 1, MAX_GROUP_INVITATION_TEXT_LENGTH);
		byte[] signature = body.getRaw(5);
		checkLength(signature, 1, HYBRID_SIGNATURE_BYTES);
		long timer = NO_AUTO_DELETE_TIMER;
		if (body.size() == 7) {
			timer = validateAutoDeleteTimer(body.getOptionalLong(6));
		}
		Author creator = clientHelper.parseAndValidateAuthor(creatorList);
		PrivateGroup privateGroup = privateGroupFactory.createPrivateGroup(
				groupName, creator, salt);
		BdfList signed = BdfList.of(
				m.getTimestamp(),
				m.getGroupId(),
				privateGroup.getId()
		);
		byte[] ed25519Sig = signature;
		if (signature.length == HYBRID_SIGNATURE_BYTES) {
			ed25519Sig = new byte[64];
			System.arraycopy(signature, 0, ed25519Sig, 0, 64);
		}
		try {
			clientHelper.verifySignature(ed25519Sig, SIGNING_LABEL_INVITE,
					signed, creator.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		BdfDictionary meta = messageEncoder.encodeMetadata(INVITE,
				privateGroup.getId(), m.getTimestamp(), timer);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateJoinMessage(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 3, 4);
		byte[] privateGroupId = body.getRaw(1);
		checkLength(privateGroupId, UniqueId.LENGTH);
		byte[] previousMessageId = body.getOptionalRaw(2);
		checkLength(previousMessageId, UniqueId.LENGTH);
		long timer = NO_AUTO_DELETE_TIMER;
		if (body.size() == 4) {
			timer = validateAutoDeleteTimer(body.getOptionalLong(3));
		}

		BdfDictionary meta = messageEncoder.encodeMetadata(JOIN,
				new GroupId(privateGroupId), m.getTimestamp(), timer);
		if (previousMessageId == null) {
			return new BdfMessageContext(meta);
		} else {
			MessageId dependency = new MessageId(previousMessageId);
			return new BdfMessageContext(meta,
					Collections.singletonList(dependency));
		}
	}

	private BdfMessageContext validateLeaveMessage(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 3, 4);
		byte[] privateGroupId = body.getRaw(1);
		checkLength(privateGroupId, UniqueId.LENGTH);
		byte[] previousMessageId = body.getOptionalRaw(2);
		checkLength(previousMessageId, UniqueId.LENGTH);
		long timer = NO_AUTO_DELETE_TIMER;
		if (body.size() == 4) {
			timer = validateAutoDeleteTimer(body.getOptionalLong(3));
		}

		BdfDictionary meta = messageEncoder.encodeMetadata(LEAVE,
				new GroupId(privateGroupId), m.getTimestamp(), timer);
		if (previousMessageId == null) {
			return new BdfMessageContext(meta);
		} else {
			MessageId dependency = new MessageId(previousMessageId);
			return new BdfMessageContext(meta,
					Collections.singletonList(dependency));
		}
	}

	private BdfMessageContext validateAbortMessage(Message m, BdfList body)
			throws FormatException {
		checkSize(body, 2);
		byte[] privateGroupId = body.getRaw(1);
		checkLength(privateGroupId, UniqueId.LENGTH);
		BdfDictionary meta = messageEncoder.encodeMetadata(ABORT,
				new GroupId(privateGroupId), m.getTimestamp(),
				NO_AUTO_DELETE_TIMER);
		return new BdfMessageContext(meta);
	}
}
