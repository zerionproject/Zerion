package org.zerionproject.app.api.avatar;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;

import java.io.IOException;
import java.io.InputStream;

public interface AvatarMessageEncoder {

	Pair<Message, BdfDictionary> encodeUpdateMessage(GroupId groupId,
			long version, String contentType, InputStream in)
			throws IOException;
}
