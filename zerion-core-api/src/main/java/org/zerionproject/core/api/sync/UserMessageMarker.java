package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface UserMessageMarker {

	void noteUserMessage(MessageId id);

	default void noteLaterPart(MessageId id) {
	}

	default void noteUserPart(MessageId id) {
	}
}
