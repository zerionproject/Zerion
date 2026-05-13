package org.briarproject.briar.privategroup;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.privategroup.GroupMessage;
import org.briarproject.briar.api.privategroup.GroupMessageFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.POST;

@Immutable
@NotNullByDefault
class GroupMessageFactoryImpl implements GroupMessageFactory {

	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;

	@Inject
	GroupMessageFactoryImpl(ClientHelper clientHelper, CryptoComponent crypto,
			IdentityManager identityManager) {
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.identityManager = identityManager;
	}

	private byte[] signHybridOrEd25519(String label, BdfList toSign,
			LocalAuthor signer)
			throws GeneralSecurityException, FormatException {
		try {
			byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
			if (mlDsaPriv != null) {
				byte[] signedBytes = clientHelper.toByteArray(toSign);
				HybridSignaturePrivateKey hybridKey =
						new HybridSignaturePrivateKey(
								signer.getPrivateKey().getEncoded(), mlDsaPriv);
				return crypto.hybridSign(label, signedBytes, hybridKey);
			}
		} catch (org.briarproject.bramble.api.db.DbException e) {
			throw new GeneralSecurityException(e);
		}
		return clientHelper.sign(label, toSign, signer.getPrivateKey());
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
			byte[] memberSignature = signHybridOrEd25519(SIGNING_LABEL_JOIN,
					toSign, member);
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
			byte[] signature = signHybridOrEd25519(SIGNING_LABEL_POST, toSign,
					member);
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
}
