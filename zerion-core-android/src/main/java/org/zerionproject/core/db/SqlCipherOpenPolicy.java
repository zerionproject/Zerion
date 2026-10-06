package org.zerionproject.core.db;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

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
