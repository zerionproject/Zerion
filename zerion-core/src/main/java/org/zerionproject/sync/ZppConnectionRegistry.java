package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ZppConnectionRegistry {

	void onConnectionOpened(int contactId, ZppSendScheduler scheduler,
			int maxRecordBytes);

	void onConnectionClosed(int contactId, ZppSendScheduler scheduler);
}
