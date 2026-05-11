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
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_MEMBERSHIP;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_POST;
import static org.briarproject.briar.api.privategroup.GroupMessageFactory.SIGNING_LABEL_SENDER_KEY_BROADCAST;
import static org.briarproject.briar.api.privategroup.MessageType.GROUP_DISSOLVED;
import static org.briarproject.briar.api.privategroup.MessageType.JOIN;
import static org.briarproject.briar.api.privategroup.MessageType.MEMBER_ADDED;
import static org.briarproject.briar.api.privategroup.MessageType.MEMBER_LEFT;
import static org.briarproject.briar.api.privategroup.MessageType.MEMBER_REMOVED;
import static org.briarproject.briar.api.privategroup.MessageType.POST;
import static org.briarproject.briar.api.privategroup.MessageType.SENDER_KEYS_POST;
import static org.briarproject.briar.api.privategroup.MessageType.SENDER_KEY_BROADCAST;
import static org.briarproject.briar.api.privategroup.PrivateGroupConstants.MAX_GROUP_POST_TEXT_LENGTH;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory.SIGNING_LABEL_INVITE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_ADDED_AUTHOR_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_ADDED_AUTHOR_NAME;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_AUTO_DELETE_TIMER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_CHAIN_KEY;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_CIPHERTEXT;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_EPOCH;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_FROM_AUTHOR_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_INITIAL_JOIN_MSG;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_LEAVING_AUTHOR_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MEMBER;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_MESSAGE_INDEX;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_NONCE;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PARENT_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PREVIOUS_MSG_ID;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_PUBLIC_SIGNING_KEY;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_READ;
import static org.briarproject.briar.privategroup.GroupConstants.KEY_REMOVED_AUTHOR_ID;
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

		// 4 = smallest (GROUP_DISSOLVED); 10 = largest (SENDER_KEYS_POST
		// with optional TTL appended). Per-type validators enforce exact
		// sizes below.
		checkSize(body, 4, 10);
		int type = body.getInt(0);

		BdfMessageContext c;
		if (type == JOIN.getInt() || type == POST.getInt()
				|| type == SENDER_KEYS_POST.getInt()) {
			// Legacy authored-record path — body[1] is the memberList tuple
			// [formatVersion, name, pubKey].
			BdfList memberList = body.getList(1);
			Author member = clientHelper.parseAndValidateAuthor(memberList);
			if (type == JOIN.getInt()) {
				c = validateJoin(m, g, body, member);
			} else if (type == POST.getInt()) {
				c = validatePost(m, g, body, member);
			} else {
				c = validateSenderKeysPost(m, g, body, member);
			}
			addMessageMetadata(c, memberList, m.getTimestamp());
		} else if (type == MEMBER_ADDED.getInt()) {
			c = validateMemberAdded(m, g, body);
		} else if (type == MEMBER_REMOVED.getInt()) {
			c = validateMemberRemoved(m, g, body);
		} else if (type == MEMBER_LEFT.getInt()) {
			c = validateMemberLeft(m, g, body);
		} else if (type == GROUP_DISSOLVED.getInt()) {
			c = validateGroupDissolved(m, g, body);
		} else if (type == SENDER_KEY_BROADCAST.getInt()) {
			c = validateSenderKeyBroadcast(m, g, body);
		} else {
			throw new InvalidMessageException("Unknown Message Type");
		}
		c.getDictionary().put(KEY_TYPE, type);
		c.getDictionary().put(KEY_TIMESTAMP, m.getTimestamp());
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
		// Body is 9 elements OR 10 — the 10th is the optional
		// per-message disappearing-messages TTL (group-v2, iOS commit
		// 8caa7ec). Absence == permanent.
		checkSize(body, 9, 10);
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

		long autoDeleteTimer = 0L;
		if (body.size() == 10) {
			Long ttl = body.getOptionalLong(9);
			if (ttl != null && ttl > 0) autoDeleteTimer = ttl;
		}

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
		if (autoDeleteTimer > 0) {
			meta.put(KEY_AUTO_DELETE_TIMER, autoDeleteTimer);
		}
		return new BdfMessageContext(meta, dependencies);
	}

	// ---------- Group-membership v2 (msgType 10–14) ----------
	//
	// Per ANDROID_GROUP_PROTOCOL_V2.md §2-§4. "authorId" inside these
	// payloads is the raw 32-byte Ed25519 signing public key (iOS
	// convention), not Briar's SHA-256-derived authorId. Signatures are
	// verified here whenever the signer's pubkey is locally derivable:
	//   - msgType 10/11/13 → group creator's pubkey from the descriptor
	//   - msgType 12 → leavingAuthorId from the payload (iOS treats
	//                  authorId == pubkey)
	//   - msgType 14 → explicit publicSigningKey field in the payload
	// PrivateGroupManagerImpl applies the state changes after validator
	// admission; non-creator removal of self or removal of creator is
	// refused there per §4 steps 5-6.

	private BdfMessageContext validateMemberAdded(Message m, Group g,
			BdfList body) throws FormatException {
		// [10, groupId(32), addedAuthorId(32), addedAuthorName, ts, sig(64)]
		checkSize(body, 6);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		if (!java.util.Arrays.equals(groupId, g.getId().getBytes())) {
			throw new FormatException();
		}
		byte[] addedAuthorId = body.getRaw(2);
		checkLength(addedAuthorId, 32);
		String addedName = body.getString(3);
		checkLength(addedName, 1, MAX_GROUP_POST_TEXT_LENGTH);
		long timestamp = body.getLong(4);
		byte[] signature = body.getRaw(5);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		byte[] signatureInput = membershipSignatureInput(
				groupId, addedAuthorId, timestamp, (byte) 0x01);
		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		org.briarproject.bramble.api.crypto.PublicKey creatorPubKey =
				pg.getCreator().getPublicKey();
		try {
			crypto.verifySignature(signature, SIGNING_LABEL_MEMBERSHIP,
					signatureInput, creatorPubKey);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_ADDED_AUTHOR_ID, addedAuthorId);
		meta.put(KEY_ADDED_AUTHOR_NAME, addedName);
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateMemberRemoved(Message m, Group g,
			BdfList body) throws FormatException {
		// [11, groupId(32), removedAuthorId(32), ts, sig(64)]
		checkSize(body, 5);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		if (!java.util.Arrays.equals(groupId, g.getId().getBytes())) {
			throw new FormatException();
		}
		byte[] removedAuthorId = body.getRaw(2);
		checkLength(removedAuthorId, 32);
		long timestamp = body.getLong(3);
		byte[] signature = body.getRaw(4);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		org.briarproject.bramble.api.crypto.PublicKey creatorPubKey =
				pg.getCreator().getPublicKey();
		// Defence-in-depth: a malicious creator can't sign their own
		// removal record. The manager re-checks this on apply too.
		if (java.util.Arrays.equals(removedAuthorId,
				creatorPubKey.getEncoded())) {
			throw new FormatException();
		}
		byte[] signatureInput = membershipSignatureInput(
				groupId, removedAuthorId, timestamp, (byte) 0x02);
		try {
			crypto.verifySignature(signature, SIGNING_LABEL_MEMBERSHIP,
					signatureInput, creatorPubKey);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_REMOVED_AUTHOR_ID, removedAuthorId);
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateMemberLeft(Message m, Group g,
			BdfList body) throws FormatException {
		// [12, groupId(32), leavingAuthorId(32), ts, sig(64)] —
		// signer is the leaving member themselves; under iOS convention
		// leavingAuthorId IS the signer's Ed25519 signing pubkey.
		checkSize(body, 5);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		if (!java.util.Arrays.equals(groupId, g.getId().getBytes())) {
			throw new FormatException();
		}
		byte[] leavingAuthorId = body.getRaw(2);
		checkLength(leavingAuthorId, 32);
		long timestamp = body.getLong(3);
		byte[] signature = body.getRaw(4);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		org.briarproject.bramble.api.crypto.PublicKey creatorPubKey =
				pg.getCreator().getPublicKey();
		// §4 step 6: the creator cannot "leave" — they must dissolve.
		if (java.util.Arrays.equals(leavingAuthorId,
				creatorPubKey.getEncoded())) {
			throw new FormatException();
		}
		byte[] signatureInput = membershipSignatureInput(
				groupId, leavingAuthorId, timestamp, (byte) 0x03);
		org.briarproject.bramble.api.crypto.PublicKey leaverPubKey =
				new org.briarproject.bramble.api.crypto.SignaturePublicKey(
						leavingAuthorId);
		try {
			crypto.verifySignature(signature, SIGNING_LABEL_MEMBERSHIP,
					signatureInput, leaverPubKey);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_LEAVING_AUTHOR_ID, leavingAuthorId);
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateGroupDissolved(Message m, Group g,
			BdfList body) throws FormatException {
		// [13, groupId(32), ts, sig(64)]
		checkSize(body, 4);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		if (!java.util.Arrays.equals(groupId, g.getId().getBytes())) {
			throw new FormatException();
		}
		long timestamp = body.getLong(2);
		byte[] signature = body.getRaw(3);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		PrivateGroup pg = privateGroupFactory.parsePrivateGroup(g);
		org.briarproject.bramble.api.crypto.PublicKey creatorPubKey =
				pg.getCreator().getPublicKey();
		byte[] signatureInput = dissolveSignatureInput(groupId, timestamp);
		try {
			crypto.verifySignature(signature, SIGNING_LABEL_MEMBERSHIP,
					signatureInput, creatorPubKey);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateSenderKeyBroadcast(Message m, Group g,
			BdfList body) throws FormatException {
		// [14, groupId(32), fromAuthorId(32), epoch, chainKey(32),
		//  publicSigningKey(32), sig(64)]
		checkSize(body, 7);
		byte[] groupId = body.getRaw(1);
		checkLength(groupId, 32);
		if (!java.util.Arrays.equals(groupId, g.getId().getBytes())) {
			throw new FormatException();
		}
		byte[] fromAuthorId = body.getRaw(2);
		checkLength(fromAuthorId, 32);
		long epochLong = body.getLong(3);
		if (epochLong < 0L || epochLong > 0xFFFFFFFFL) {
			throw new FormatException();
		}
		int epoch = (int) epochLong;
		byte[] chainKey = body.getRaw(4);
		checkLength(chainKey, 32);
		byte[] publicSigningKey = body.getRaw(5);
		checkLength(publicSigningKey, 32);
		byte[] signature = body.getRaw(6);
		checkLength(signature, 1, MAX_SIGNATURE_LENGTH);

		// iOS convention treats authorId == pubkey, so fromAuthorId
		// must match publicSigningKey. If they diverge the record is
		// malformed (or a confused-deputy attempt).
		if (!java.util.Arrays.equals(fromAuthorId, publicSigningKey)) {
			throw new FormatException();
		}
		byte[] signatureInput = senderKeyBroadcastSignatureInput(
				groupId, fromAuthorId, epoch, chainKey);
		org.briarproject.bramble.api.crypto.PublicKey signerPubKey =
				new org.briarproject.bramble.api.crypto.SignaturePublicKey(
						publicSigningKey);
		try {
			crypto.verifySignature(signature,
					SIGNING_LABEL_SENDER_KEY_BROADCAST,
					signatureInput, signerPubKey);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		BdfDictionary meta = new BdfDictionary();
		meta.put(KEY_FROM_AUTHOR_ID, fromAuthorId);
		meta.put(KEY_EPOCH, epoch);
		meta.put(KEY_CHAIN_KEY, chainKey);
		meta.put(KEY_PUBLIC_SIGNING_KEY, publicSigningKey);
		meta.put(KEY_SIGNATURE, signature);
		return new BdfMessageContext(meta);
	}

	// Signed-input layouts per ANDROID_GROUP_PROTOCOL_V2.md §3.
	// Big-endian timestamps / epoch; trailing single byte = action
	// discriminator.

	private byte[] membershipSignatureInput(byte[] groupId,
			byte[] targetAuthorId, long timestamp, byte action) {
		byte[] input = new byte[32 + 32 + 8 + 1];
		System.arraycopy(groupId, 0, input, 0, 32);
		System.arraycopy(targetAuthorId, 0, input, 32, 32);
		for (int i = 0; i < 8; i++) {
			input[64 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		input[72] = action;
		return input;
	}

	private byte[] dissolveSignatureInput(byte[] groupId, long timestamp) {
		byte[] input = new byte[32 + 8 + 1];
		System.arraycopy(groupId, 0, input, 0, 32);
		for (int i = 0; i < 8; i++) {
			input[32 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		input[40] = (byte) 0x04;
		return input;
	}

	private byte[] senderKeyBroadcastSignatureInput(byte[] groupId,
			byte[] fromAuthorId, int epoch, byte[] chainKey) {
		byte[] input = new byte[32 + 32 + 4 + 32 + 1];
		System.arraycopy(groupId, 0, input, 0, 32);
		System.arraycopy(fromAuthorId, 0, input, 32, 32);
		for (int i = 0; i < 4; i++) {
			input[64 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(chainKey, 0, input, 68, 32);
		input[100] = (byte) 0x05;
		return input;
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
