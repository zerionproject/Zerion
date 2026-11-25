package org.zerionproject.app.api.messaging;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_DELIVERABLE_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface MessagingConstants {

	int MAX_PRIVATE_MESSAGE_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 2048;

	int PRIVATE_MESSAGE_OVERHEAD_BYTES = 2048;

	int MAX_DELIVERABLE_PRIVATE_MESSAGE_TEXT_LENGTH =
			MAX_DELIVERABLE_MESSAGE_BODY_LENGTH
					- PRIVATE_MESSAGE_OVERHEAD_BYTES;

	int MAX_ATTACHMENTS_PER_MESSAGE = 10;

}
