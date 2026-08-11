package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.transport.agreement.MessageType.ACTIVATE;
import static org.zerionproject.core.transport.agreement.MessageType.KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_IS_SESSION;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_MESSAGE_TYPE;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_TRANSPORT_ID;

@Immutable
@NotNullByDefault
class MessageEncoderImpl implements MessageEncoder {

	private final ClientHelper clientHelper;
	private final Clock clock;

	@Inject
	MessageEncoderImpl(ClientHelper clientHelper, Clock clock) {
		this.clientHelper = clientHelper;
		this.clock = clock;
	}

	@Override
	public Message encodeKeyMessage(GroupId contactGroupId,
			TransportId transportId, PublicKey publicKey) {
		BdfList body = BdfList.of(
				KEY.getValue(),
				transportId.getString(),
				publicKey.getEncoded());
		return encodeMessage(contactGroupId, body);
	}

	@Override
	public Message encodeActivateMessage(GroupId contactGroupId,
			TransportId transportId, MessageId previousMessageId) {
		BdfList body = BdfList.of(
				ACTIVATE.getValue(),
				transportId.getString(),
				previousMessageId);
		return encodeMessage(contactGroupId, body);
	}

	@Override
	public BdfDictionary encodeMessageMetadata(TransportId transportId,
			MessageType type, boolean local) {
		return BdfDictionary.of(
				new BdfEntry(MSG_KEY_IS_SESSION, false),
				new BdfEntry(MSG_KEY_TRANSPORT_ID, transportId.getString()),
				new BdfEntry(MSG_KEY_MESSAGE_TYPE, type.getValue()),
				new BdfEntry(MSG_KEY_LOCAL, local));
	}

	private Message encodeMessage(GroupId contactGroupId, BdfList body) {
		try {
			return clientHelper.createMessage(contactGroupId,
					clock.currentTimeMillis(), clientHelper.toByteArray(body));
		} catch (FormatException e) {
			throw new AssertionError();
		}
	}
}
