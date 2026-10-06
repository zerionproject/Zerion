package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.db.DbException;

public class PcsPersistenceException extends RuntimeException {

	public PcsPersistenceException(DbException cause) {
		super(cause);
	}
}
