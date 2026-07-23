package org.zerionproject.core.versioning;

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

import static org.zerionproject.core.api.sync.ClientId.MAX_CLIENT_ID_LENGTH;
import static org.zerionproject.core.util.ValidationUtils.checkLength;
import static org.zerionproject.core.util.ValidationUtils.checkSize;
import static org.zerionproject.core.versioning.ClientVersioningConstants.MSG_KEY_LOCAL;
import static org.zerionproject.core.versioning.ClientVersioningConstants.MSG_KEY_UPDATE_VERSION;

@Immutable
@NotNullByDefault
class ClientVersioningValidator extends BdfMessageValidator {

	ClientVersioningValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		checkSize(body, 2);
		BdfList states = body.getList(0);
		int size = states.size();
		for (int i = 0; i < size; i++) {
			BdfList clientState = states.getList(i);
			checkSize(clientState, 4);
			String clientId = clientState.getString(0);
			checkLength(clientId, 1, MAX_CLIENT_ID_LENGTH);
			int majorVersion = clientState.getInt(1);
			if (majorVersion < 0) throw new FormatException();
			int minorVersion = clientState.getInt(2);
			if (minorVersion < 0) throw new FormatException();
			clientState.getBoolean(3);
		}
		long updateVersion = body.getLong(1);
		if (updateVersion < 0) throw new FormatException();
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_UPDATE_VERSION, updateVersion);
		meta.put(MSG_KEY_LOCAL, false);
		return new BdfMessageContext(meta);
	}
}
