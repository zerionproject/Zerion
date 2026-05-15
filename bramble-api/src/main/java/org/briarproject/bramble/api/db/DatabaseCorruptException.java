package org.briarproject.bramble.api.db;

public class DatabaseCorruptException extends DbException {

	public DatabaseCorruptException() {
		super();
	}

	public DatabaseCorruptException(Throwable t) {
		super(t);
	}
}
