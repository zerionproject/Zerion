package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TorActivitySink {

	enum Activity {

		CONNECTION_DATA,

		CONNECTION_ATTEMPT,

		SYNC_PENDING,

		COVER_TRAFFIC
	}

	void noteActivity(Activity activity);

	TorActivitySink NONE = activity -> {
	};
}
