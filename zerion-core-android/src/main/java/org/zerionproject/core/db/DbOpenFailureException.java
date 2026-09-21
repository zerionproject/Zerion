package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Thrown when an existing database cannot be opened or validated. The
 * original files are left in place; the failure class describes what went
 * wrong so that the caller can present a recoverable error instead of
 * initialising a fresh identity over the user's data.
 */
@NotNullByDefault
public class DbOpenFailureException extends DbException {

	private final SqlCipherOpenPolicy.FailureClass failureClass;

	DbOpenFailureException(SqlCipherOpenPolicy.FailureClass failureClass,
			@Nullable Throwable cause) {
		super(cause == null ? new Exception(failureClass.name()) : cause);
		this.failureClass = failureClass;
	}

	public String getFailureClassName() {
		return failureClass.name();
	}
}
