package org.briarproject.briar.api.attachment;

import org.briarproject.bramble.api.db.DbException;

/**
 * Thrown when an attachment exists but is not yet fully delivered
 * (e.g., manifest or chunks are still being synchronized).
 * Callers should retry after a delay.
 */
public class AttachmentNotYetAvailableException extends DbException {

	public AttachmentNotYetAvailableException() {
		super();
	}
}
