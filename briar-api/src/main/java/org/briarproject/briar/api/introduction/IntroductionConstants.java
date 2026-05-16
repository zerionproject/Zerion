package org.briarproject.briar.api.introduction;

import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;

public interface IntroductionConstants {

	int MAX_INTRODUCTION_TEXT_LENGTH = MAX_MESSAGE_BODY_LENGTH - 1024;

	String LABEL_SESSION_ID = "org.briarproject.briar.introduction/SESSION_ID";

	String LABEL_MASTER_KEY = "org.briarproject.briar.introduction/MASTER_KEY";

	String LABEL_ALICE_MAC_KEY =
			"org.briarproject.briar.introduction/ALICE_MAC_KEY";

	String LABEL_BOB_MAC_KEY =
			"org.briarproject.briar.introduction/BOB_MAC_KEY";

	String LABEL_AUTH_MAC = "org.briarproject.briar.introduction/AUTH_MAC";

	String LABEL_AUTH_SIGN = "org.briarproject.briar.introduction/AUTH_SIGN";

	String LABEL_AUTH_NONCE = "org.briarproject.briar.introduction/AUTH_NONCE";

	String LABEL_ACTIVATE_MAC =
			"org.briarproject.briar.introduction/ACTIVATE_MAC";

	/**
	 * v1.7 Phase 5b — Introduction protocol hybrid KEM scaffolding.
	 * <p>
	 * When flipped to {@code true}, the Introduction protocol bumps
	 * {@code MAJOR_VERSION} to 2 and the wire format carries:
	 * <ul>
	 *   <li>{@code ACCEPT.ephemeralPublicKey} — 1,216 B hybrid
	 *       (X25519 32 B || ML-KEM-768 1,184 B), was 32 B.</li>
	 *   <li>{@code ACCEPT.kemCiphertext} — new 1,088 B field carrying the
	 *       ML-KEM-768 ciphertext encapsulated to the peer's hybrid pubkey,
	 *       or null on the first ACCEPT from a peer (sentinel).</li>
	 * </ul>
	 * <p>
	 * Activation requires the matching state-machine refactor in
	 * {@code IntroduceeProtocolEngine} that splits the master-key
	 * derivation into a pre-master (DH + own-KEM, used for AUTH MAC) and a
	 * final master (DH + both-KEMs, used for ACTIVATE MAC + downstream
	 * contact key). Until that lands, this flag stays false and the
	 * existing X25519-only path runs unchanged.
	 * <p>
	 * iOS-parity coordination required before flipping in a release.
	 */
	boolean INTRODUCTION_HYBRID_KEM_ENABLED = false;

	String LABEL_PRE_MASTER_KEY =
			"org.briarproject.briar.introduction/PRE_MASTER_KEY";

	int HYBRID_EPHEMERAL_PUBLIC_KEY_BYTES = 1216;

	int INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES = 1184;

	int INTRODUCTION_KEM_CIPHERTEXT_BYTES = 1088;

}
