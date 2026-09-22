package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Decides what to do with an existing database directory when it is opened.
 * The decision is a pure function of two facts so that every failure class
 * can be exercised without a device: whether the setup marker is present,
 * which is written when a database is created and removed once a local
 * identity has been stored, and what a probe of the existing file found.
 *
 * Existing user data is never deleted because the probe threw. A database
 * that opened and holds an identity is reused. A database that opened and
 * provably holds no identity is empty and may be recreated. A database that
 * could not be opened is only ever moved aside, and only when the marker
 * says that setup never completed; otherwise the open fails closed with the
 * failure class attached so that the caller can report it.
 */
@NotNullByDefault
final class SqlCipherOpenPolicy {

	enum Probe {
		OPENED_WITH_IDENTITY,
		OPENED_WITHOUT_IDENTITY,
		FAILED
	}

	enum Action {
		REOPEN,
		RESET_EMPTY,
		QUARANTINE_INCOMPLETE,
		FAIL_CLOSED
	}

	enum FailureClass {
		LOCKED,
		KEY_OR_HEADER,
		CORRUPT,
		STORAGE_FULL,
		DISK_IO,
		MIGRATION,
		UNKNOWN
	}

	private SqlCipherOpenPolicy() {
	}

	/**
	 * What a successful connection to an existing file tells us. Only a
	 * database that has both expected tables and no identity row is
	 * provably empty; a file missing a table is not a Zerion database of
	 * this schema (a downgrade, a foreign file) and must be treated as a
	 * failed probe so it is never deleted as empty.
	 */
	static Probe probe(boolean settingsTablePresent,
			boolean identityTablePresent, long identityRows) {
		if (!settingsTablePresent || !identityTablePresent) {
			return Probe.FAILED;
		}
		return identityRows > 0 ? Probe.OPENED_WITH_IDENTITY
				: Probe.OPENED_WITHOUT_IDENTITY;
	}

	static Action decide(boolean setupMarkerPresent, Probe probe) {
		switch (probe) {
			case OPENED_WITH_IDENTITY:
				return Action.REOPEN;
			case OPENED_WITHOUT_IDENTITY:
				return Action.RESET_EMPTY;
			case FAILED:
				return setupMarkerPresent ? Action.QUARANTINE_INCOMPLETE
						: Action.FAIL_CLOSED;
			default:
				throw new AssertionError();
		}
	}

	/**
	 * Classifies an open failure from the exception chain. Class names are
	 * matched as strings so that the policy has no dependency on the
	 * platform database classes and can be tested on the JVM.
	 */
	static FailureClass classify(@Nullable Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			String name = c.toString();
			String message = c.getMessage() == null ? "" : c.getMessage();
			if (name.contains("SQLiteDatabaseLockedException")
					|| message.contains("Database locked")
					|| message.contains("database is locked")) {
				return FailureClass.LOCKED;
			}
			if (name.contains("SQLiteFullException")
					|| message.contains("SQLITE_FULL")
					|| message.contains("database or disk is full")) {
				return FailureClass.STORAGE_FULL;
			}
			if (name.contains("SQLiteDiskIOException")
					|| message.contains("disk I/O error")) {
				return FailureClass.DISK_IO;
			}
			if (message.contains("file is not a database")
					|| name.contains("SQLiteDatabaseCorruptException")
					&& message.contains("not a database")) {
				return FailureClass.KEY_OR_HEADER;
			}
			if (name.contains("SQLiteDatabaseCorruptException")
					|| message.contains("SQLITE_CORRUPT")
					|| message.contains("malformed")) {
				return FailureClass.CORRUPT;
			}
			if (name.contains("DataTooOldException")
					|| name.contains("DataTooNewException")
					|| name.contains("MigrationException")) {
				return FailureClass.MIGRATION;
			}
		}
		return FailureClass.UNKNOWN;
	}
}
