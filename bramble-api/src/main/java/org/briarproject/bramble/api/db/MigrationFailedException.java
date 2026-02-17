package org.briarproject.bramble.api.db;

/**
 * Thrown when an H2-to-SQLCipher database migration fails. This is distinct
 * from a generic {@link DbException} so the UI can show a migration-specific
 * recovery screen instead of a generic "database error" message.
 */
public class MigrationFailedException extends DbException {

	public MigrationFailedException() {
		super();
	}

	public MigrationFailedException(Throwable cause) {
		super(cause);
	}
}
