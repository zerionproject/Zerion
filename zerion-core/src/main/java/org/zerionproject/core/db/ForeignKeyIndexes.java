package org.zerionproject.core.db;

final class ForeignKeyIndexes {

	static final String[] STATEMENTS = {
			"CREATE INDEX IF NOT EXISTS contactsByLocalAuthorId"
					+ " ON contacts (localAuthorId)",
			"CREATE INDEX IF NOT EXISTS groupVisibilitiesByGroupId"
					+ " ON groupVisibilities (groupId)",
			"CREATE INDEX IF NOT EXISTS messagesByGroupId"
					+ " ON messages (groupId)",
			"CREATE INDEX IF NOT EXISTS messageDependenciesByMessageId"
					+ " ON messageDependencies (messageId)",
			"CREATE INDEX IF NOT EXISTS messageDependenciesByGroupId"
					+ " ON messageDependencies (groupId)",
			"CREATE INDEX IF NOT EXISTS offersByContactId"
					+ " ON offers (contactId)",
			"CREATE INDEX IF NOT EXISTS statusesByGroupId"
					+ " ON statuses (groupId)",
			"CREATE INDEX IF NOT EXISTS outgoingKeysByContactId"
					+ " ON outgoingKeys (contactId)",
			"CREATE INDEX IF NOT EXISTS outgoingKeysByPendingContactId"
					+ " ON outgoingKeys (pendingContactId)",
			"CREATE INDEX IF NOT EXISTS incomingKeysByKeySetId"
					+ " ON incomingKeys (keySetId)"
	};

	private ForeignKeyIndexes() {
	}
}
