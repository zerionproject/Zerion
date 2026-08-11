package org.zerionproject.core.api.plugin;

public interface B4Constants {

	boolean B4_ROTATION_ENABLED = true;

	int ROTATION_MIN_DAYS = 5;

	int ROTATION_TARGET_DAYS = 7;

	int ROTATION_MAX_DAYS = 14;

	int FORCE_EXPIRE_DAYS = 90;

	int ANNOUNCE_RETRY_BACKOFF_S = 60;

	String WIRE_KEY_ONION3 = "onion3";
	String WIRE_KEY_ONION3_NEXT = "onion3_next";
	String WIRE_KEY_ONION3_ANNOUNCED_AT_MS = "onion3_announced_at_ms";

	String WIRE_KEY_ONION3_PUBLISH_NONCE = "onion3_publish_nonce";

	long[] B4_REBROADCAST_DELAYS_SECONDS = { 30L, 60L, 120L };

	String B4_SETTINGS_NAMESPACE = "b4";

	String B4_CONTACT_ONION3_PENDING_KEY_PREFIX = "contact_onion3_pending.";
	String B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX =
			"contact_onion3_announced_at_ms.";
	String B4_PEER_ROTATION_STATE_KEY_PREFIX = "peer_rotation_state.";

	String B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX =
			"contact_pending_dial_failures.";

	String B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX =
			"contact_pending_dial_succeeded.";

	int B4_PENDING_DIAL_FAILURE_THRESHOLD = 3;

	long B4_ANNOUNCE_RATE_LIMIT_MS = 3_600_000L;

	String B4_LOCAL_KEY_PREFIX = "_local_";

	String B4_LOCAL_FALLBACK_ONION_KEY = B4_LOCAL_KEY_PREFIX
			+ "onion3_fallback";

	String B4_LOCAL_CONTACT_ID_KEY = B4_LOCAL_KEY_PREFIX
			+ "onion3_contact_id";

	long B4_ACCEPT_CORRELATION_WINDOW_MS = 30_000L;

	String B4_ALICE_ONION3_CURRENT_KEY = "alice_onion3_current";
	String B4_ALICE_ONION3_NEXT_KEY = "alice_onion3_next";
	String B4_ALICE_ONION3_NEXT_PRIVKEY_KEY = "alice_onion3_next_privkey";
	String B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY =
			"alice_onion3_announced_at_ms";
	String B4_ALICE_LAST_ROTATION_TIME_MS_KEY = "alice_last_rotation_time_ms";
	String B4_ALICE_ROTATION_PHASE_KEY = "alice_rotation_phase";
}
