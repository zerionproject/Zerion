package org.zerionproject.core.api.crypto;

public enum DecryptionResult {

	SUCCESS,

	INVALID_CIPHERTEXT,

	KEY_STRENGTHENER_ERROR,

	INVALID_PASSWORD,
	/**
	 * The password was right but the key could not be re-encrypted and
	 * stored durably under the new password, or the stored value did not
	 * decrypt with it; the old password is unchanged.
	 */
	KEY_REPLACEMENT_FAILED
}
