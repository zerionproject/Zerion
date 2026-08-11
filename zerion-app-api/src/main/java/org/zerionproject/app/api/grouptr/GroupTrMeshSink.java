package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface GroupTrMeshSink {

	boolean isOfflineMode();

	void floodRecord(int contactId, byte[] record, long timestamp);
}
