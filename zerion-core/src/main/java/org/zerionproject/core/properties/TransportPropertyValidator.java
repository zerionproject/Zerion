package org.zerionproject.core.properties;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.BdfMessageContext;
import org.zerionproject.core.api.client.BdfMessageValidator;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.plugin.TransportId.MAX_TRANSPORT_ID_LENGTH;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_TRANSPORT_ID;
import static org.zerionproject.core.api.properties.TransportPropertyConstants.MSG_KEY_VERSION;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;

@Immutable
@NotNullByDefault
class TransportPropertyValidator extends BdfMessageValidator {

	TransportPropertyValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock, false);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		checkSize(body, 3);
		String transportId = body.getString(0);
		checkLength(transportId, 1, MAX_TRANSPORT_ID_LENGTH);
		long version = body.getLong(1);
		if (version < 0) throw new FormatException();
		BdfDictionary dictionary = body.getDictionary(2);
		clientHelper.parseAndValidateTransportProperties(dictionary);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TRANSPORT_ID, transportId);
		meta.put(MSG_KEY_VERSION, version);
		meta.put(MSG_KEY_LOCAL, false);
		return new BdfMessageContext(meta);
	}
}
