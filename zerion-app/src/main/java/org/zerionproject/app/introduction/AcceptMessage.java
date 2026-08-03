package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.client.SessionId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class AcceptMessage extends AbstractIntroductionMessage {

	private final SessionId sessionId;
	private final PublicKey ephemeralPublicKey;
	private final long acceptTimestamp;
	private final Map<TransportId, TransportProperties> transportProperties;
	@Nullable
	private final byte[] mlDsaPubKey;
	@Nullable
	private final byte[] mlKemEphemeralPublicKey;

	protected AcceptMessage(MessageId messageId, GroupId groupId,
			long timestamp, @Nullable MessageId previousMessageId,
			SessionId sessionId, PublicKey ephemeralPublicKey,
			long acceptTimestamp,
			Map<TransportId, TransportProperties> transportProperties,
			long autoDeleteTimer, @Nullable byte[] mlDsaPubKey,
			@Nullable byte[] mlKemEphemeralPublicKey) {
		super(messageId, groupId, timestamp, previousMessageId,
				autoDeleteTimer);
		this.sessionId = sessionId;
		this.ephemeralPublicKey = ephemeralPublicKey;
		this.acceptTimestamp = acceptTimestamp;
		this.transportProperties = transportProperties;
		this.mlDsaPubKey = mlDsaPubKey;
		this.mlKemEphemeralPublicKey = mlKemEphemeralPublicKey;
	}

	protected AcceptMessage(MessageId messageId, GroupId groupId,
			long timestamp, @Nullable MessageId previousMessageId,
			SessionId sessionId, PublicKey ephemeralPublicKey,
			long acceptTimestamp,
			Map<TransportId, TransportProperties> transportProperties,
			long autoDeleteTimer, @Nullable byte[] mlDsaPubKey) {
		this(messageId, groupId, timestamp, previousMessageId, sessionId,
				ephemeralPublicKey, acceptTimestamp, transportProperties,
				autoDeleteTimer, mlDsaPubKey, null);
	}

	protected AcceptMessage(MessageId messageId, GroupId groupId,
			long timestamp, @Nullable MessageId previousMessageId,
			SessionId sessionId, PublicKey ephemeralPublicKey,
			long acceptTimestamp,
			Map<TransportId, TransportProperties> transportProperties,
			long autoDeleteTimer) {
		this(messageId, groupId, timestamp, previousMessageId, sessionId,
				ephemeralPublicKey, acceptTimestamp, transportProperties,
				autoDeleteTimer, null, null);
	}

	public SessionId getSessionId() {
		return sessionId;
	}

	public PublicKey getEphemeralPublicKey() {
		return ephemeralPublicKey;
	}

	public long getAcceptTimestamp() {
		return acceptTimestamp;
	}

	public Map<TransportId, TransportProperties> getTransportProperties() {
		return transportProperties;
	}

	@Nullable
	public byte[] getMlDsaPubKey() {
		return mlDsaPubKey;
	}

	@Nullable
	public byte[] getMlKemEphemeralPublicKey() {
		return mlKemEphemeralPublicKey;
	}

}
