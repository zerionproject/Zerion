package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.BdfMessageContext;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.client.BdfMessageValidator;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
class OnionAuthValidator extends BdfMessageValidator {

	static final String MSG_KEY_TYPE = "type";
	static final String MSG_KEY_KEY_VERSION = "keyVersion";
	static final String MSG_KEY_LOCAL = "local";

	OnionAuthValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock, false);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws FormatException {
		OnionAuthRecords.Record r = OnionAuthRecords.parse(body);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TYPE, r.type);
		meta.put(MSG_KEY_KEY_VERSION, r.keyVersion);
		meta.put(MSG_KEY_LOCAL, false);
		return new BdfMessageContext(meta);
	}
}
