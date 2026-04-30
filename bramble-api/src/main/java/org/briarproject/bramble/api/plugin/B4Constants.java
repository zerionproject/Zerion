package org.briarproject.bramble.api.plugin;

/**
 * B.4 — Onion rotation constants.
 *
 * <p>See {@code docs/wire/B4_PLAN.md} (sibling iOS repo,
 * {@code Zerion Ios/docs/wire/B4_PLAN.md}) for the full plan and the
 * {@code B3_B4_SPEC_v1.5.0.md} Part 2 for the protocol design.
 *
 * <p>Long-term linkability decay: the v3 onion address is the only
 * persistent piece of metadata each peer of the user knows. Without
 * rotation, "I am still talking to the same user as last week" is an
 * unforgeable observation any single contact can make forever. B.4
 * rotates onions periodically — opportunistically when both peers are
 * online together within a 5–14 day window, peer-acked retirement of
 * the old onion, force-expire after 90 days for genuinely abandoned
 * peers.
 *
 * <p>All numeric constants are <b>spec-frozen</b> and must match iOS
 * byte-for-byte. Tweaking any of them on one platform without the other
 * will desync rotation cadence and risk old-onion retirement before
 * peer migration.
 */
public interface B4Constants {

	/**
	 * Master gate for the B.4 onion rotation feature.
	 *
	 * <p>When {@code false} (default): no rotation triggers fire, no
	 * {@code tor.onion3_next} property is ever written, no {@code
	 * ADD_ONION} for a second concurrent service. Wire-byte-identical
	 * with v1.4. Inbound {@code tor.onion3_next} property keys from a
	 * v1.5 peer are silently dropped (unknown-property semantics) so a
	 * gate-off receiver does not break a gate-on sender.
	 *
	 * <p>When {@code true}: opportunistic rotation per the spec —
	 * sync-session-start trigger checks the 5/7/14 day window, generates
	 * {@code onion_next} when fired, sends per-peer announces, retires
	 * the old onion on the per-peer ack-driven completion path or at
	 * 90 days regardless.
	 *
	 * <p><b>Do not flip without a coordinated joint debug build with
	 * the iOS team.</b> Both platforms must flip simultaneously and a
	 * cross-platform rotation interop test (Phase 5 in the plan) must
	 * pass before either side ships a release with the gate on.
	 */
	boolean B4_ROTATION_ENABLED = false;

	/** Earliest a rotation can fire after the previous one (days). */
	int ROTATION_MIN_DAYS = 5;

	/**
	 * UX-display label only — "rotates around weekly" (days). No
	 * behavioural effect; the trigger uses {@link #ROTATION_MIN_DAYS}
	 * and {@link #ROTATION_MAX_DAYS} as the actual window edges.
	 */
	int ROTATION_TARGET_DAYS = 7;

	/**
	 * Force-rotate even with zero active peers when this many days have
	 * elapsed since the previous rotation (days). When fired, both
	 * onions sit live until peers come back, at which point the announce
	 * is sent and they migrate per the normal flow.
	 */
	int ROTATION_MAX_DAYS = 14;

	/**
	 * Retire the old onion this many days after rotation regardless of
	 * peer ack state. Catches genuinely abandoned peers — the 1% of
	 * contacts who haven't connected in three months are presumed lost.
	 */
	int FORCE_EXPIRE_DAYS = 90;

	/**
	 * Minimum interval between announce retries to a single peer when
	 * the previous send failed (seconds). Prevents an announce-loop on
	 * a flaky channel.
	 */
	int ANNOUNCE_RETRY_BACKOFF_S = 60;

	/**
	 * Wire-format property keys on the existing {@code
	 * TransportPropertyManager} update record. Must match iOS exactly
	 * — see {@code B4_PLAN.md} §3.
	 */
	String WIRE_KEY_ONION3 = "tor.onion3";
	String WIRE_KEY_ONION3_NEXT = "tor.onion3_next";
	String WIRE_KEY_ONION3_ANNOUNCED_AT_MS = "tor.onion3_announced_at_ms";

	/** Local {@code SettingsManager} namespace for B.4 state. */
	String B4_SETTINGS_NAMESPACE = "b4";

	/**
	 * Per-contact storage key prefixes. Local-only — keys never leave
	 * the device, so the iOS underscore vs Android dot naming gap is
	 * fine.
	 */
	String B4_CONTACT_ONION3_PENDING_KEY_PREFIX = "contact_onion3_pending.";
	String B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX =
			"contact_onion3_announced_at_ms.";
	String B4_PEER_ROTATION_STATE_KEY_PREFIX = "peer_rotation_state.";

	/** Alice's own rotation state keys (no contact id suffix). */
	String B4_ALICE_ONION3_CURRENT_KEY = "alice_onion3_current";
	String B4_ALICE_ONION3_NEXT_KEY = "alice_onion3_next";
	String B4_ALICE_LAST_ROTATION_TIME_MS_KEY = "alice_last_rotation_time_ms";
	String B4_ALICE_ROTATION_PHASE_KEY = "alice_rotation_phase";
}
