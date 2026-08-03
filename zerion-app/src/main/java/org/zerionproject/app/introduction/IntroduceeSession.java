package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.app.api.client.SessionId;
import org.zerionproject.app.api.introduction.Role;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.app.api.introduction.Role.INTRODUCEE;
import static org.zerionproject.app.introduction.IntroduceeState.AWAIT_ACTIVATE;
import static org.zerionproject.app.introduction.IntroduceeState.START;

@Immutable
@NotNullByDefault
class IntroduceeSession extends Session<IntroduceeState>
		implements PeerSession {

	private final GroupId contactGroupId;
	private final Author introducer;
	private final Local local;
	private final Remote remote;
	@Nullable
	private final byte[] masterKey;
	@Nullable
	private final Map<TransportId, KeySetId> transportKeys;

	IntroduceeSession(SessionId sessionId, IntroduceeState state,
			long requestTimestamp, GroupId contactGroupId, Author introducer,
			Local local, Remote remote, @Nullable byte[] masterKey,
			@Nullable Map<TransportId, KeySetId> transportKeys) {
		super(sessionId, state, requestTimestamp);
		this.contactGroupId = contactGroupId;
		this.introducer = introducer;
		this.local = local;
		this.remote = remote;
		this.masterKey = masterKey;
		this.transportKeys = transportKeys;
	}

	static IntroduceeSession getInitial(GroupId contactGroupId,
			SessionId sessionId, Author introducer, boolean localIsAlice,
			Author remoteAuthor) {
		Local local =
				new Local(localIsAlice, null, -1, null, null, null, -1, null,
						null);
		Remote remote =
				new Remote(!localIsAlice, remoteAuthor, null, null, null, -1,
						null, null);
		return new IntroduceeSession(sessionId, START, -1, contactGroupId,
				introducer, local, remote, null, null);
	}

	static IntroduceeSession addRemoteRequest(IntroduceeSession s,
			IntroduceeState state, RequestMessage m) {
		Remote remote = new Remote(s.remote, m.getMessageId());
		return new IntroduceeSession(s.getSessionId(), state, m.getTimestamp(),
				s.contactGroupId, s.introducer, s.local, remote, s.masterKey,
				s.transportKeys);
	}

	static IntroduceeSession addLocalAccept(IntroduceeSession s,
			IntroduceeState state, Message acceptMessage,
			PublicKey ephemeralPublicKey, PrivateKey ephemeralPrivateKey,
			long acceptTimestamp,
			Map<TransportId, TransportProperties> transportProperties,
			@Nullable byte[] localMlDsaPubKey) {
		return addLocalAccept(s, state, acceptMessage, ephemeralPublicKey,
				ephemeralPrivateKey, acceptTimestamp, transportProperties,
				localMlDsaPubKey, null, null);
	}

	static IntroduceeSession addLocalAccept(IntroduceeSession s,
			IntroduceeState state, Message acceptMessage,
			PublicKey ephemeralPublicKey, PrivateKey ephemeralPrivateKey,
			long acceptTimestamp,
			Map<TransportId, TransportProperties> transportProperties,
			@Nullable byte[] localMlDsaPubKey,
			@Nullable byte[] mlKemEphemeralPublicKey,
			@Nullable byte[] mlKemEphemeralPrivateKey) {
		Local local = new Local(s.local.alice, acceptMessage.getId(),
				acceptMessage.getTimestamp(), ephemeralPublicKey,
				ephemeralPrivateKey, transportProperties, acceptTimestamp,
				null, localMlDsaPubKey, mlKemEphemeralPublicKey,
				mlKemEphemeralPrivateKey);
		return new IntroduceeSession(s.getSessionId(), state,
				s.getRequestTimestamp(), s.contactGroupId, s.introducer, local,
				s.remote, s.masterKey, s.transportKeys);
	}

	static IntroduceeSession addRemoteAccept(IntroduceeSession s,
			IntroduceeState state, AcceptMessage m) {
		Remote remote =
				new Remote(s.remote.alice, s.remote.author, m.getMessageId(),
						m.getEphemeralPublicKey(), m.getTransportProperties(),
						m.getAcceptTimestamp(), s.remote.macKey,
						m.getMlDsaPubKey(), m.getMlKemEphemeralPublicKey());
		return new IntroduceeSession(s.getSessionId(), state,
				s.getRequestTimestamp(), s.contactGroupId, s.introducer,
				s.local, remote, s.masterKey, s.transportKeys);
	}

	static IntroduceeSession addLocalAuth(IntroduceeSession s,
			IntroduceeState state, Message m, SecretKey masterKey,
			SecretKey aliceMacKey, SecretKey bobMacKey) {
		return addLocalAuth(s, state, m, masterKey, aliceMacKey, bobMacKey,
				null);
	}

	static IntroduceeSession addLocalAuth(IntroduceeSession s,
			IntroduceeState state, Message m, SecretKey masterKey,
			SecretKey aliceMacKey, SecretKey bobMacKey,
			@Nullable byte[] ownKemSecret) {
		Local local = new Local(s.local.alice, m.getId(), m.getTimestamp(),
				s.local.ephemeralPublicKey, s.local.ephemeralPrivateKey,
				s.local.transportProperties, s.local.acceptTimestamp,
				s.local.alice ? aliceMacKey.getBytes() : bobMacKey.getBytes(),
				s.local.mlDsaPubKey, s.local.mlKemEphemeralPublicKey,
				s.local.mlKemEphemeralPrivateKey, ownKemSecret);
		Remote remote = new Remote(s.remote.alice, s.remote.author,
				s.remote.lastMessageId, s.remote.ephemeralPublicKey,
				s.remote.transportProperties, s.remote.acceptTimestamp,
				s.remote.alice ? aliceMacKey.getBytes() : bobMacKey.getBytes(),
				s.remote.mlDsaPubKey, s.remote.mlKemEphemeralPublicKey);
		return new IntroduceeSession(s.getSessionId(), state,
				s.getRequestTimestamp(), s.contactGroupId, s.introducer, local,
				remote, masterKey.getBytes(), s.transportKeys);
	}

	static IntroduceeSession withMasterKey(IntroduceeSession s,
			byte[] masterKey) {
		return new IntroduceeSession(s.getSessionId(), s.getState(),
				s.getRequestTimestamp(), s.contactGroupId, s.introducer,
				s.local, s.remote, masterKey, s.transportKeys);
	}

	static IntroduceeSession awaitActivate(IntroduceeSession s, AuthMessage m,
			Message sent, @Nullable Map<TransportId, KeySetId> transportKeys) {
		Local local = Local.clear(s.local, sent.getId(), sent.getTimestamp());
		Remote remote = Remote.clear(s.remote, m.getMessageId());
		return new IntroduceeSession(s.getSessionId(), AWAIT_ACTIVATE,
				s.getRequestTimestamp(), s.contactGroupId, s.introducer, local,
				remote, null, transportKeys);
	}

	static IntroduceeSession clear(IntroduceeSession s, IntroduceeState state,
			@Nullable MessageId lastLocalMessageId, long localTimestamp,
			@Nullable MessageId lastRemoteMessageId) {
		Local local =
				new Local(s.local.alice, lastLocalMessageId, localTimestamp,
						null, null, null, -1, null, null);
		Remote remote =
				new Remote(s.remote.alice, s.remote.author, lastRemoteMessageId,
						null, null, -1, null, null);
		return new IntroduceeSession(s.getSessionId(), state,
				s.getRequestTimestamp(), s.contactGroupId, s.introducer, local,
				remote, null, null);
	}

	@Override
	Role getRole() {
		return INTRODUCEE;
	}

	@Override
	public GroupId getContactGroupId() {
		return contactGroupId;
	}

	@Override
	public long getLocalTimestamp() {
		return local.lastMessageTimestamp;
	}

	@Nullable
	@Override
	public MessageId getLastLocalMessageId() {
		return local.lastMessageId;
	}

	@Nullable
	@Override
	public MessageId getLastRemoteMessageId() {
		return remote.lastMessageId;
	}

	Author getIntroducer() {
		return introducer;
	}

	public Local getLocal() {
		return local;
	}

	public Remote getRemote() {
		return remote;
	}

	@Nullable
	byte[] getMasterKey() {
		return masterKey;
	}

	@Nullable
	Map<TransportId, KeySetId> getTransportKeys() {
		return transportKeys;
	}

	abstract static class Common {
		final boolean alice;
		@Nullable
		final MessageId lastMessageId;
		@Nullable
		final PublicKey ephemeralPublicKey;
		@Nullable
		final Map<TransportId, TransportProperties> transportProperties;
		final long acceptTimestamp;
		@Nullable
		final byte[] macKey;
		@Nullable
		final byte[] mlDsaPubKey;
		@Nullable
		final byte[] mlKemEphemeralPublicKey;

		private Common(boolean alice, @Nullable MessageId lastMessageId,
				@Nullable PublicKey ephemeralPublicKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey,
				@Nullable byte[] mlKemEphemeralPublicKey) {
			this.alice = alice;
			this.lastMessageId = lastMessageId;
			this.ephemeralPublicKey = ephemeralPublicKey;
			this.transportProperties = transportProperties;
			this.acceptTimestamp = acceptTimestamp;
			this.macKey = macKey;
			this.mlDsaPubKey = mlDsaPubKey;
			this.mlKemEphemeralPublicKey = mlKemEphemeralPublicKey;
		}
	}

	static class Local extends Common {
		final long lastMessageTimestamp;
		@Nullable
		final PrivateKey ephemeralPrivateKey;
		@Nullable
		final byte[] mlKemEphemeralPrivateKey;
		@Nullable
		final byte[] ownKemSecret;

		Local(boolean alice, @Nullable MessageId lastMessageId,
				long lastMessageTimestamp,
				@Nullable PublicKey ephemeralPublicKey,
				@Nullable PrivateKey ephemeralPrivateKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey) {
			this(alice, lastMessageId, lastMessageTimestamp,
					ephemeralPublicKey, ephemeralPrivateKey,
					transportProperties, acceptTimestamp, macKey, mlDsaPubKey,
					null, null, null);
		}

		Local(boolean alice, @Nullable MessageId lastMessageId,
				long lastMessageTimestamp,
				@Nullable PublicKey ephemeralPublicKey,
				@Nullable PrivateKey ephemeralPrivateKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey,
				@Nullable byte[] mlKemEphemeralPublicKey,
				@Nullable byte[] mlKemEphemeralPrivateKey) {
			this(alice, lastMessageId, lastMessageTimestamp,
					ephemeralPublicKey, ephemeralPrivateKey,
					transportProperties, acceptTimestamp, macKey, mlDsaPubKey,
					mlKemEphemeralPublicKey, mlKemEphemeralPrivateKey, null);
		}

		Local(boolean alice, @Nullable MessageId lastMessageId,
				long lastMessageTimestamp,
				@Nullable PublicKey ephemeralPublicKey,
				@Nullable PrivateKey ephemeralPrivateKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey,
				@Nullable byte[] mlKemEphemeralPublicKey,
				@Nullable byte[] mlKemEphemeralPrivateKey,
				@Nullable byte[] ownKemSecret) {
			super(alice, lastMessageId, ephemeralPublicKey, transportProperties,
					acceptTimestamp, macKey, mlDsaPubKey,
					mlKemEphemeralPublicKey);
			this.lastMessageTimestamp = lastMessageTimestamp;
			this.ephemeralPrivateKey = ephemeralPrivateKey;
			this.mlKemEphemeralPrivateKey = mlKemEphemeralPrivateKey;
			this.ownKemSecret = ownKemSecret;
		}

		private static Local clear(Local s,
				@Nullable MessageId lastMessageId, long lastMessageTimestamp) {
			if (s.mlKemEphemeralPrivateKey != null) {
				java.util.Arrays.fill(s.mlKemEphemeralPrivateKey, (byte) 0);
			}
			if (s.ownKemSecret != null) {
				java.util.Arrays.fill(s.ownKemSecret, (byte) 0);
			}
			return new Local(s.alice, lastMessageId, lastMessageTimestamp,
					null, null, s.transportProperties, s.acceptTimestamp,
					s.macKey, s.mlDsaPubKey, null, null, null);
		}
	}

	static class Remote extends Common {
		final Author author;

		Remote(boolean alice, Author author,
				@Nullable MessageId lastMessageId,
				@Nullable PublicKey ephemeralPublicKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey) {
			this(alice, author, lastMessageId, ephemeralPublicKey,
					transportProperties, acceptTimestamp, macKey, mlDsaPubKey,
					null);
		}

		Remote(boolean alice, Author author,
				@Nullable MessageId lastMessageId,
				@Nullable PublicKey ephemeralPublicKey, @Nullable
				Map<TransportId, TransportProperties> transportProperties,
				long acceptTimestamp, @Nullable byte[] macKey,
				@Nullable byte[] mlDsaPubKey,
				@Nullable byte[] mlKemEphemeralPublicKey) {
			super(alice, lastMessageId, ephemeralPublicKey, transportProperties,
					acceptTimestamp, macKey, mlDsaPubKey,
					mlKemEphemeralPublicKey);
			this.author = author;
		}

		private Remote(Remote s, @Nullable MessageId lastMessageId) {
			this(s.alice, s.author, lastMessageId, s.ephemeralPublicKey,
					s.transportProperties, s.acceptTimestamp, s.macKey,
					s.mlDsaPubKey, s.mlKemEphemeralPublicKey);
		}

		private static Remote clear(Remote s,
				@Nullable MessageId lastMessageId) {
			return new Remote(s.alice, s.author, lastMessageId, null,
					s.transportProperties, s.acceptTimestamp, s.macKey,
					s.mlDsaPubKey, null);
		}
	}

}
