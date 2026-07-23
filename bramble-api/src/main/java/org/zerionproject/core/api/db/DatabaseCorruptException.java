package org.zerionproject.core.api.db;

public class DatabaseCorruptException extends DbException {

	public DatabaseCorruptException() {
		super();
	}

	public DatabaseCorruptException(Throwable t) {
		super(t);
	}
}
