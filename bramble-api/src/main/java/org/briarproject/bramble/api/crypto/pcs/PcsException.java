package org.briarproject.bramble.api.crypto.pcs;

/**
 * Exception thrown when a PCS operation fails.
 * <p>
 * This can occur due to:
 * <ul>
 *   <li>Message number too far ahead (exceeds MAX_SKIP)</li>
 *   <li>Message number in the past with no stored skipped key</li>
 *   <li>Invalid or corrupted PCS header</li>
 *   <li>State desynchronization between peers</li>
 * </ul>
 */
public class PcsException extends Exception {

	public PcsException(String message) {
		super(message);
	}

	public PcsException(String message, Throwable cause) {
		super(message, cause);
	}
}
