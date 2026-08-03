package org.zerionproject.app.api.privategroup;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface PrivateGroupConstants {

	int MAX_GROUP_NAME_LENGTH = 100;

	int GROUP_SALT_LENGTH = 32;

	int MAX_GROUP_POST_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	int MAX_GROUP_INVITATION_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

}
