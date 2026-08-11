package org.zerionproject.core.api.db;

public interface MigrationListener {

	void onDatabaseMigration();

	void onDatabaseCompaction();
}
