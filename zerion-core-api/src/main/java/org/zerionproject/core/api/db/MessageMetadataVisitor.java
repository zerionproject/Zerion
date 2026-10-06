package org.zerionproject.core.api.db;

import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MessageMetadataVisitor<E extends Exception> {

	void visit(MessageId m, Metadata metadata) throws DbException, E;
}
