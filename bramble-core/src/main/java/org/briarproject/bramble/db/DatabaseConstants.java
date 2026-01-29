package org.briarproject.bramble.db;

import org.briarproject.bramble.api.settings.Settings;

interface DatabaseConstants {

	
	int MAX_OFFERED_MESSAGES = 1000;

	
	String DB_SETTINGS_NAMESPACE = "db";

	
	String SCHEMA_VERSION_KEY = "schemaVersion";

	
	String DIRTY_KEY = "dirty";
}
