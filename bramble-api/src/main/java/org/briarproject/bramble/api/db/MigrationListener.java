package org.briarproject.bramble.api.db;

public interface MigrationListener {

	void onDatabaseMigration();

	void onDatabaseCompaction();
}
