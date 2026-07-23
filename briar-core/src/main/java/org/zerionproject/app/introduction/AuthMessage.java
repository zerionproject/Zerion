package org.zerionproject.app.introduction;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.client.SessionId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;

@Immutable
@NotNullByDefault
class AuthMessage extends AbstractIntroductionMessage {

	private final SessionId sessionId;
	private final byte[] mac, signature;
	@Nullable
	private final byte[] kemCiphertext;

	protected AuthMessage(MessageId messageId, GroupId groupId,
			long timestamp, MessageId previousMessageId, SessionId sessionId,
			byte[] mac, byte[] signature) {
		this(messageId, groupId, timestamp, previousMessageId, sessionId,
				mac, signature, null);
	}

	protected AuthMessage(MessageId messageId, GroupId groupId,
			long timestamp, MessageId previousMessageId, SessionId sessionId,
			byte[] mac, byte[] signature, @Nullable byte[] kemCiphertext) {
		super(messageId, groupId, timestamp, previousMessageId,
				NO_AUTO_DELETE_TIMER);
		this.sessionId = sessionId;
		this.mac = mac;
		this.signature = signature;
		this.kemCiphertext = kemCiphertext;
	}

	public SessionId getSessionId() {
		return sessionId;
	}

	public byte[] getMac() {
		return mac;
	}

	public byte[] getSignature() {
		return signature;
	}

	@Nullable
	public byte[] getKemCiphertext() {
		return kemCiphertext;
	}

}
