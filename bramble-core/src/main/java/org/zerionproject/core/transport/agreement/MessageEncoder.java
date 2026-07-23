package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface MessageEncoder {

	Message encodeKeyMessage(GroupId contactGroupId,
			TransportId transportId, PublicKey publicKey);

	Message encodeActivateMessage(GroupId contactGroupId,
			TransportId transportId, MessageId previousMessageId);

	BdfDictionary encodeMessageMetadata(TransportId transportId,
			MessageType type, boolean local);
}
