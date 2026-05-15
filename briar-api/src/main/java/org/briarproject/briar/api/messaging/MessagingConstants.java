package org.briarproject.briar.api.messaging;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface MessagingConstants {

	int MAX_PRIVATE_MESSAGE_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 2048;

	int MAX_ATTACHMENTS_PER_MESSAGE = 10;

}
