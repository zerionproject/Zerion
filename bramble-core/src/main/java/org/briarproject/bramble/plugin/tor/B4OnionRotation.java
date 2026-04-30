package org.briarproject.bramble.plugin.tor;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * B.4 — Onion rotation state machine.
 *
 * <p>Skeleton only. Mirrors iOS subtask 1.1 ({@code
 * B4OnionRotation.swift}). State transitions, sync-session trigger,
 * publisher (generate / register / per-peer announce / completion
 * check / force-expire) and receiver (handle inbound {@code
 * tor.onion3_next} / prefer-pending / atomic-swap) all land in Phase 4
 * once the {@code onionwrapper} library PR (Phase 3) provides
 * concurrent {@code ADD_ONION} support and field-level encryption on
 * transport properties (Phase 3.5) lands.
 *
 * <p>See {@code docs/wire/B4_PLAN.md} on the iOS sibling repo for the
 * full plan and {@code B3_B4_SPEC_v1.5.0.md} Part 2 for the protocol
 * design (opportunistic + peer-acked retirement + force-expire).
 */
@NotNullByDefault
public class B4OnionRotation {

	/**
	 * Alice's overall rotation phase. Single-flighted via {@code
	 * compareAndSet} on the persisted {@code
	 * alice_rotation_phase} key — see Phase 4 implementation.
	 */
	public enum RotationPhase {
		/** No rotation in progress; only {@code onion_current} is live. */
		IDLE,
		/**
		 * {@code onion_next} generated, both onions running, sending
		 * per-peer announces.
		 */
		ANNOUNCING,
		/**
		 * Transient state during atomic promotion; observed only on
		 * crash-recovery if the app died mid-retirement. Recovery code
		 * resumes the promotion idempotently on next startup.
		 */
		COMPLETE,
	}

	/**
	 * Per-contact rotation state on Alice's side. Persisted under {@code
	 * b4.peer_rotation_state.<contactId>} via {@code SettingsManager}.
	 */
	public enum PeerRotationState {
		/** Peer dials Alice's old onion; no announce sent yet. */
		CURRENT,
		/** Peer received the rotation announce, knows about {@code onion_next}. */
		PRE_ANNOUNCED,
		/**
		 * Alice received an inbound connection from this peer on
		 * {@code onion_next}. Migration confirmed.
		 */
		MIGRATED,
	}
}
