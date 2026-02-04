package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoState;
import org.briarproject.briar.api.privategroup.senderkeys.RekeyRequest;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistribution;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributionFactory;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributor;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
public class SenderKeyDistributorImpl implements SenderKeyDistributor {

	private static final String SIGNATURE_LABEL = "org.zerion/SENDER_KEY_DIST_SIG";

	private final ClientHelper clientHelper;
	private final ContactManager contactManager;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final MessagingManager messagingManager;
	private final SenderKeyDistributionFactory distributionFactory;
	private final SenderKeyManager senderKeyManager;

	@Inject
	public SenderKeyDistributorImpl(
			ClientHelper clientHelper,
			ContactManager contactManager,
			CryptoComponent crypto,
			IdentityManager identityManager,
			MessagingManager messagingManager,
			SenderKeyDistributionFactory distributionFactory,
			SenderKeyManager senderKeyManager
	) {
		this.clientHelper = clientHelper;
		this.contactManager = contactManager;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.messagingManager = messagingManager;
		this.distributionFactory = distributionFactory;
		this.senderKeyManager = senderKeyManager;
	}

	@Override
	public void distributeSenderKey(
			Transaction txn,
			GroupId groupId,
			SenderKey senderKey
	) throws DbException {
		Collection<ContactId> memberContacts = getGroupMemberContacts(txn, groupId);
		for (ContactId contactId : memberContacts) {
			distributeSenderKeyToMember(txn, groupId, senderKey, contactId);
		}
	}

	@Override
	public void distributeSenderKeyToMember(
			Transaction txn,
			GroupId groupId,
			SenderKey senderKey,
			ContactId contactId
	) throws DbException {
		try {
			LocalAuthor localAuthor = identityManager.getLocalAuthor(txn);
			PrivateKey privateKey = localAuthor.getPrivateKey();

			GroupId conversationGroupId = messagingManager.getConversationId(txn, contactId);

			SenderKeyDistribution distribution = distributionFactory.createSenderKeyDistribution(
					conversationGroupId,
					groupId,
					senderKey,
					privateKey
			);

			Message message = distribution.getMessage();
			BdfDictionary meta = new BdfDictionary();
			meta.put("type", "sender_key_distribution");
			meta.put("targetGroup", groupId.getBytes());

			clientHelper.addLocalMessage(txn, message, meta, false, false);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void handleReceivedSenderKey(
			Transaction txn,
			ContactId senderContactId,
			SenderKeyDistributionFactory.ParsedSenderKeyDistribution parsed
	) throws DbException {
		try {
			Contact sender = contactManager.getContact(txn, senderContactId);
			Author senderAuthor = sender.getAuthor();

			byte[] signatureInput = buildSignatureInput(
					parsed.getTargetGroupId(),
					parsed.getChainKey(),
					parsed.getEpoch(),
					parsed.getMessageIndex(),
					parsed.getCreatedAt(),
					parsed.getAuthorId()
			);

			PublicKey senderPublicKey = senderAuthor.getPublicKey();

			boolean valid = crypto.verifySignature(
					parsed.getSignature(),
					SIGNATURE_LABEL,
					signatureInput,
					senderPublicKey
			);

			if (!valid) {
				throw new DbException();
			}

			AuthorId authorId = new AuthorId(parsed.getAuthorId());
			if (!authorId.equals(senderAuthor.getId())) {
				throw new DbException();
			}

			SenderKey receivedKey = new SenderKey(
					parsed.getTargetGroupId(),
					authorId,
					new SecretKey(parsed.getChainKey()),
					parsed.getEpoch(),
					parsed.getMessageIndex(),
					parsed.getCreatedAt(),
					false,
					SenderKeyState.ACTIVE
			);

			senderKeyManager.storeSenderKey(txn, receivedKey);
		} catch (GeneralSecurityException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void triggerRekey(
			Transaction txn,
			GroupId groupId,
			RekeyRequest.Reason reason,
			AuthorId affectedMember
	) throws DbException {
		GroupCryptoState.RekeyReason stateReason;
		switch (reason) {
			case JOIN:
				stateReason = GroupCryptoState.RekeyReason.JOIN;
				break;
			case LEAVE:
				stateReason = GroupCryptoState.RekeyReason.LEAVE;
				break;
			case KICK:
				stateReason = GroupCryptoState.RekeyReason.KICK;
				break;
			case EPOCH:
				stateReason = GroupCryptoState.RekeyReason.EPOCH;
				break;
			default:
				throw new IllegalArgumentException("Unknown rekey reason: " + reason);
		}

		SenderKey newKey = senderKeyManager.rekeyGroup(txn, groupId, stateReason);
		distributeSenderKey(txn, groupId, newKey);
	}

	@Override
	public Collection<ContactId> getGroupMemberContacts(
			Transaction txn,
			GroupId groupId
	) throws DbException {
		AuthorId localAuthorId = identityManager.getLocalAuthor(txn).getId();
		Map<AuthorId, SenderKey> allKeys = senderKeyManager.getAllSenderKeys(txn, groupId);
		Collection<ContactId> contacts = new ArrayList<>();

		for (AuthorId authorId : allKeys.keySet()) {
			if (!authorId.equals(localAuthorId)) {
				try {
					Contact contact = contactManager.getContact(txn, authorId, localAuthorId);
					contacts.add(contact.getId());
				} catch (DbException e) {
					// Contact not found, skip
				}
			}
		}

		return contacts;
	}

	private byte[] buildSignatureInput(
			GroupId groupId,
			byte[] chainKey,
			int epoch,
			int messageIndex,
			long createdAt,
			byte[] authorId
	) {
		byte[] groupIdBytes = groupId.getBytes();

		byte[] input = new byte[groupIdBytes.length + chainKey.length +
				4 + 4 + 8 + authorId.length];

		int offset = 0;
		System.arraycopy(groupIdBytes, 0, input, offset, groupIdBytes.length);
		offset += groupIdBytes.length;
		System.arraycopy(chainKey, 0, input, offset, chainKey.length);
		offset += chainKey.length;

		input[offset++] = (byte) (epoch >> 24);
		input[offset++] = (byte) (epoch >> 16);
		input[offset++] = (byte) (epoch >> 8);
		input[offset++] = (byte) epoch;

		input[offset++] = (byte) (messageIndex >> 24);
		input[offset++] = (byte) (messageIndex >> 16);
		input[offset++] = (byte) (messageIndex >> 8);
		input[offset++] = (byte) messageIndex;

		input[offset++] = (byte) (createdAt >> 56);
		input[offset++] = (byte) (createdAt >> 48);
		input[offset++] = (byte) (createdAt >> 40);
		input[offset++] = (byte) (createdAt >> 32);
		input[offset++] = (byte) (createdAt >> 24);
		input[offset++] = (byte) (createdAt >> 16);
		input[offset++] = (byte) (createdAt >> 8);
		input[offset++] = (byte) createdAt;

		System.arraycopy(authorId, 0, input, offset, authorId.length);

		return input;
	}
}
