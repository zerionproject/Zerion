package org.briarproject.bramble.api.db;

/**
 * Thrown when the database is corrupted and cannot be opened.
 * This is an unrecoverable error that requires the user to reset
 * their account data.
 */
public class DatabaseCorruptException extends DbException {

	public DatabaseCorruptException() {
		super();
	}

	public DatabaseCorruptException(Throwable t) {
		super(t);
	}
}
