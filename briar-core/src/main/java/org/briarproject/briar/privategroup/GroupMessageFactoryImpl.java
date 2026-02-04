package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto.EncryptedGroupMessage;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.privategroup.MessageType.SENDER_KEYS_POST;

@Immutable
@NotNullByDefault
class GroupMessageFactoryImpl implements GroupMessageFactory {

	private final ClientHelper clientHelper;
	private final GroupMessageCrypto groupMessageCrypto;

	@Inject
	GroupMessageFactoryImpl(ClientHelper clientHelper,
			GroupMessageCrypto groupMessageCrypto) {
		this.clientHelper = clientHelper;
		this.groupMessageCrypto = groupMessageCrypto;
	}

	@Override
	public GroupMessage createJoinMessage(GroupId groupId, long timestamp,
			LocalAuthor creator) {

		return createJoinMessage(groupId, timestamp, creator, null);
	}

	@Override
	public GroupMessage createJoinMessage(GroupId groupId, long timestamp,
			LocalAuthor member, long inviteTimestamp, byte[] creatorSignature) {

		BdfList invite = BdfList.of(inviteTimestamp, creatorSignature);
		return createJoinMessage(groupId, timestamp, member, invite);
	}

	private GroupMessage createJoinMessage(GroupId groupId, long timestamp,
			LocalAuthor member, @Nullable BdfList invite) {
		try {
			BdfList memberList = clientHelper.toList(member);
			BdfList toSign = BdfList.of(
					groupId,
					timestamp,
					memberList,
					invite
			);
			byte[] memberSignature = clientHelper.sign(SIGNING_LABEL_JOIN,
					toSign, member.getPrivateKey());
			BdfList body = BdfList.of(
					JOIN.getInt(),
					memberList,
					invite,
					memberSignature
			);
			Message m = clientHelper.createMessage(groupId, timestamp, body);
			return new GroupMessage(m, null, member);
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException(e);
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public GroupMessage createGroupMessage(GroupId groupId, long timestamp,
			@Nullable MessageId parentId, LocalAuthor member, String text,
			MessageId previousMsgId) {
		try {
			BdfList memberList = clientHelper.toList(member);
			BdfList toSign = BdfList.of(
					groupId,
					timestamp,
					memberList,
					parentId,
					previousMsgId,
					text
			);
			byte[] signature = clientHelper.sign(SIGNING_LABEL_POST, toSign,
					member.getPrivateKey());
			BdfList body = BdfList.of(
					POST.getInt(),
					memberList,
					parentId,
					previousMsgId,
					text,
					signature
			);
			Message m = clientHelper.createMessage(groupId, timestamp, body);
			return new GroupMessage(m, parentId, member);
		} catch (GeneralSecurityException e) {
			throw new IllegalArgumentException(e);
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public GroupMessage createSenderKeysGroupMessage(Transaction txn,
			GroupId groupId, long timestamp, @Nullable MessageId parentId,
			LocalAuthor member, String text, MessageId previousMsgId)
			throws DbException, GeneralSecurityException {
		try {
			BdfList memberList = clientHelper.toList(member);
			byte[] plaintext = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

			EncryptedGroupMessage encrypted = groupMessageCrypto.encryptGroupMessage(
					txn,
					groupId,
					plaintext,
					timestamp,
					member.getPrivateKey()
			);

			BdfList body = BdfList.of(
					SENDER_KEYS_POST.getInt(),
					memberList,
					parentId,
					previousMsgId,
					encrypted.getCiphertext(),
					encrypted.getNonce(),
					encrypted.getEpoch(),
					encrypted.getMessageIndex(),
					encrypted.getSignature()
			);

			Message m = clientHelper.createMessage(groupId, timestamp, body);
			return new GroupMessage(m, parentId, member);
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}
}
