package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.BdfMessageContext;
import org.zerionproject.core.api.client.BdfMessageValidator;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.plugin.TransportId.MAX_TRANSPORT_ID_LENGTH;
import static org.zerionproject.core.api.system.Clock.MIN_REASONABLE_TIME_MS;
import static org.zerionproject.core.transport.agreement.MessageType.ACTIVATE;
import static org.zerionproject.core.transport.agreement.MessageType.KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_PUBLIC_KEY;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;

@Immutable
@NotNullByDefault
class TransportKeyAgreementValidator extends BdfMessageValidator {

	private final MessageEncoder messageEncoder;

	TransportKeyAgreementValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock,
			MessageEncoder messageEncoder) {
		super(clientHelper, metadataEncoder, clock);
		this.messageEncoder = messageEncoder;
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		MessageType type = MessageType.fromValue(body.getInt(0));
		if (type == KEY) return validateKeyMessage(m.getTimestamp(), body);
		else if (type == ACTIVATE) return validateActivateMessage(body);
		else throw new AssertionError();
	}

	private BdfMessageContext validateKeyMessage(long timestamp, BdfList body)
			throws FormatException {
		if (timestamp < MIN_REASONABLE_TIME_MS) throw new FormatException();
		checkSize(body, 3);
		String transportId = body.getString(1);
		checkLength(transportId, 1, MAX_TRANSPORT_ID_LENGTH);
		byte[] publicKey = body.getRaw(2);
		checkLength(publicKey, 1, MAX_AGREEMENT_PUBLIC_KEY_BYTES);
		BdfDictionary meta = messageEncoder.encodeMessageMetadata(
				new TransportId(transportId), KEY, false);
		meta.put(MSG_KEY_PUBLIC_KEY, publicKey);
		return new BdfMessageContext(meta);
	}

	private BdfMessageContext validateActivateMessage(BdfList body)
			throws FormatException {
		checkSize(body, 3);
		String transportId = body.getString(1);
		checkLength(transportId, 1, MAX_TRANSPORT_ID_LENGTH);
		byte[] previousMessageId = body.getRaw(2);
		checkLength(previousMessageId, MessageId.LENGTH);
		BdfDictionary meta = messageEncoder.encodeMessageMetadata(
				new TransportId(transportId), ACTIVATE, false);
		MessageId dependency = new MessageId(previousMessageId);
		return new BdfMessageContext(meta, singletonList(dependency));
	}
}
