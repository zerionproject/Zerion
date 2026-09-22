package org.zerionproject.core.api.crypto;

/**
 * Thrown when a key strengthener was requested for an encryption but could
 * not strengthen the key. The caller decides whether to fail the operation
 * or to keep the previously stored ciphertext; the key is never written
 * with a weaker binding than the one requested.
 */
public class KeyStrengthenerException extends RuntimeException {

	public KeyStrengthenerException(Throwable cause) {
		super(cause);
	}
}
