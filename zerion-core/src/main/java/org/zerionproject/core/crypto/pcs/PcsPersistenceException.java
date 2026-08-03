package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.db.DbException;

/**
 * Wraps a DbException raised while persisting PCS ratchet state so the failure
 * can travel through the java.util.function.Consumer save callbacks, whose
 * accept method cannot declare a checked exception. The stream layer catches
 * this and aborts the frame rather than advancing the ratchet past state it
 * could not save.
 */
public class PcsPersistenceException extends RuntimeException {

	public PcsPersistenceException(DbException cause) {
		super(cause);
	}
}
