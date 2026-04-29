package org.briarproject.bramble.api.contact;

/**
 * B.3 — In-band hybrid-key signing constants.
 *
 * See {@code docs/wire/B3_RECORD_PLACEMENT.md} for the full spec.
 *
 * <p>The B.3 proof binds the long-term ML-KEM-768 portion of a peer's
 * static hybrid identity to their Ed25519 signing key, closing the
 * trust-on-first-use downgrade path on the post-quantum half of the
 * handshake. The proof rides as slot[4] of the {@code CONTACT_INFO}
 * BDF list (extending the existing 4-slot
 * {@code [authorList, propsDict, signature, timestamp]} layout).
 *
 * <p>The flag is wire-incompatible: a 1.5 sender with the flag on writes
 * 5 slots; a 1.4 receiver tolerates the trailing slot via BDF's
 * end-marker form and ignores it. A 1.5 receiver with the flag on
 * verifies the proof if 5 slots arrive, accepts a 4-slot record only
 * from peers advertising {@code messaging.minorVersion} &lt; 5.
 *
 * <p><b>Do not flip this without a coordinated joint debug build with
 * the iOS team.</b> The flag must flip on both sides simultaneously, and
 * an interop iOS↔Android contact-add over Tor must be observed to verify
 * slot[4] before either side ships a release with the flag on.
 */
public interface B3Constants {

	/**
	 * Master gate for the B.3 in-band hybrid-key signing feature.
	 *
	 * <p>When {@code false} (default): {@code CONTACT_INFO} is the
	 * legacy 4-slot list, {@code messaging.minorVersion} is 4, no
	 * receiver-side B.3 verification is performed. Byte-identical with
	 * v1.4 wire format.
	 *
	 * <p>When {@code true}: {@code CONTACT_INFO} grows to 5 slots with
	 * the B.3 proof at slot[4], {@code messaging.minorVersion} is 5,
	 * and the receiver enforces the state machine from
	 * {@code B3_RECORD_PLACEMENT.md} §4. Hard-rejects on missing-from-v5,
	 * malformed sig, or verify-fail.
	 */
	boolean B3_PROOF_ENABLED = false;

	/**
	 * Temporary debug-logging gate for emulator testing of the B.3
	 * encode / decode path.
	 *
	 * <p>When {@code true}, the encode + decode + verify checkpoints in
	 * {@code ContactExchangeManagerImpl} emit short {@code Logger}
	 * lines tagged {@code [B3]} so you can follow the wire-format flow
	 * in {@code adb logcat}. Logs only contain lengths, slot counts,
	 * pass/fail flags, and short non-secret SHA-256 prefixes — no
	 * private keys, no signing material, no message content.
	 *
	 * <p>Default {@code false}. Compile-time-final — when off, the
	 * compiler folds the entire log block out of the bytecode, so
	 * release builds emit nothing regardless of log-level
	 * configuration. Flip to {@code true}, rebuild, install, observe
	 * via {@code adb logcat | grep B3}. After testing, flip back to
	 * {@code false} before any production rebuild.
	 */
	boolean B3_DEBUG_LOG = false;

	/**
	 * Domain separator for the B.3 signature input. UTF-8, 22 bytes,
	 * no NUL.
	 */
	String B3_KEY_PROOF_LABEL = "ZERION_PQ_KEY_PROOF_v1";

	/**
	 * Domain separator used as the BLAKE2b-256 key for sessionId
	 * derivation. UTF-8, 27 bytes, no NUL.
	 */
	String B3_HANDSHAKE_SESSION_LABEL = "ZERION_HANDSHAKE_SESSION_v1";

	/** Role byte: lower-pubkey side (Alice / initiator). */
	byte B3_ROLE_ALICE = 0x01;

	/** Role byte: higher-pubkey side (Bob / responder). */
	byte B3_ROLE_BOB = 0x02;

	/** Length of the BLAKE2b-256 sessionId in bytes. */
	int B3_SESSION_ID_LEN = 32;

	/**
	 * Length of the ML-KEM-768 public key in bytes. Equal to
	 * {@code MLKEM_ENCAPSULATION_KEY_SIZE} from PcsConstants.
	 */
	int B3_PQ_PUB_LEN = 1184;

	/** Length of an Ed25519 signature in bytes. */
	int B3_SIG_LEN = 64;

	/**
	 * Total signature input length: 4+22 + 1 + 4+32 + 4+1184 = 1251.
	 */
	int B3_SIG_INPUT_LEN = 1251;
}
