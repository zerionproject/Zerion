package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistribution;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyDistributionFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.messaging.MessageTypes.SENDER_KEY_DISTRIBUTION;

@Immutable
@NotNullByDefault
public class SenderKeyDistributionFactoryImpl implements SenderKeyDistributionFactory {

	private static final String SIGNATURE_LABEL = "org.zerion/SENDER_KEY_DIST_SIG";

	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final Clock clock;

	@Inject
	public SenderKeyDistributionFactoryImpl(
			ClientHelper clientHelper,
			CryptoComponent crypto,
			Clock clock
	) {
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.clock = clock;
	}

	@Override
	public SenderKeyDistribution createSenderKeyDistribution(
			GroupId conversationGroupId,
			GroupId targetGroupId,
			SenderKey senderKey,
			PrivateKey privateKey
	) throws FormatException {
		try {
			byte[] dataToSign = buildSignatureInput(targetGroupId, senderKey);

			byte[] signature = crypto.sign(SIGNATURE_LABEL, dataToSign, privateKey);

			BdfList body = BdfList.of(
					SENDER_KEY_DISTRIBUTION,
					targetGroupId.getBytes(),
					senderKey.getChainKey().getBytes(),
					senderKey.getEpoch(),
					senderKey.getMessageIndex(),
					senderKey.getCreatedAt(),
					senderKey.getAuthorId().getBytes(),
					signature
			);

			Message message = clientHelper.createMessage(
					conversationGroupId,
					clock.currentTimeMillis(),
					body
			);

			return new SenderKeyDistribution(message, targetGroupId, senderKey, signature);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	@Override
	public ParsedSenderKeyDistribution parseSenderKeyDistribution(byte[] body)
			throws FormatException {
		BdfList list = clientHelper.toList(body);
		if (list.size() < 8) {
			throw new FormatException();
		}

		int type = list.getInt(0);
		if (type != SENDER_KEY_DISTRIBUTION) {
			throw new FormatException();
		}

		byte[] targetGroupIdBytes = list.getRaw(1);
		byte[] chainKey = list.getRaw(2);
		int epoch = list.getInt(3);
		int messageIndex = list.getInt(4);
		long createdAt = list.getLong(5);
		byte[] authorIdBytes = list.getRaw(6);
		byte[] signature = list.getRaw(7);

		return new ParsedSenderKeyDistributionImpl(
				new GroupId(targetGroupIdBytes),
				chainKey,
				epoch,
				messageIndex,
				createdAt,
				authorIdBytes,
				signature
		);
	}

	private byte[] buildSignatureInput(GroupId groupId, SenderKey senderKey) {
		byte[] groupIdBytes = groupId.getBytes();
		byte[] chainKeyBytes = senderKey.getChainKey().getBytes();
		byte[] authorIdBytes = senderKey.getAuthorId().getBytes();

		byte[] input = new byte[groupIdBytes.length + chainKeyBytes.length +
				4 + 4 + 8 + authorIdBytes.length];

		int offset = 0;
		System.arraycopy(groupIdBytes, 0, input, offset, groupIdBytes.length);
		offset += groupIdBytes.length;
		System.arraycopy(chainKeyBytes, 0, input, offset, chainKeyBytes.length);
		offset += chainKeyBytes.length;

		input[offset++] = (byte) (senderKey.getEpoch() >> 24);
		input[offset++] = (byte) (senderKey.getEpoch() >> 16);
		input[offset++] = (byte) (senderKey.getEpoch() >> 8);
		input[offset++] = (byte) senderKey.getEpoch();

		input[offset++] = (byte) (senderKey.getMessageIndex() >> 24);
		input[offset++] = (byte) (senderKey.getMessageIndex() >> 16);
		input[offset++] = (byte) (senderKey.getMessageIndex() >> 8);
		input[offset++] = (byte) senderKey.getMessageIndex();

		long createdAt = senderKey.getCreatedAt();
		input[offset++] = (byte) (createdAt >> 56);
		input[offset++] = (byte) (createdAt >> 48);
		input[offset++] = (byte) (createdAt >> 40);
		input[offset++] = (byte) (createdAt >> 32);
		input[offset++] = (byte) (createdAt >> 24);
		input[offset++] = (byte) (createdAt >> 16);
		input[offset++] = (byte) (createdAt >> 8);
		input[offset++] = (byte) createdAt;

		System.arraycopy(authorIdBytes, 0, input, offset, authorIdBytes.length);

		return input;
	}

	private static class ParsedSenderKeyDistributionImpl
			implements ParsedSenderKeyDistribution {

		private final GroupId targetGroupId;
		private final byte[] chainKey;
		private final int epoch;
		private final int messageIndex;
		private final long createdAt;
		private final byte[] authorId;
		private final byte[] signature;

		ParsedSenderKeyDistributionImpl(
				GroupId targetGroupId,
				byte[] chainKey,
				int epoch,
				int messageIndex,
				long createdAt,
				byte[] authorId,
				byte[] signature
		) {
			this.targetGroupId = targetGroupId;
			this.chainKey = chainKey;
			this.epoch = epoch;
			this.messageIndex = messageIndex;
			this.createdAt = createdAt;
			this.authorId = authorId;
			this.signature = signature;
		}

		@Override
		public GroupId getTargetGroupId() {
			return targetGroupId;
		}

		@Override
		public byte[] getChainKey() {
			return chainKey;
		}

		@Override
		public int getEpoch() {
			return epoch;
		}

		@Override
		public int getMessageIndex() {
			return messageIndex;
		}

		@Override
		public long getCreatedAt() {
			return createdAt;
		}

		@Override
		public byte[] getAuthorId() {
			return authorId;
		}

		@Override
		public byte[] getSignature() {
			return signature;
		}
	}
}
