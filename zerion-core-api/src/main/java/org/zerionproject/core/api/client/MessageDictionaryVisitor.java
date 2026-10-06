package org.zerionproject.core.api.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MessageDictionaryVisitor {

	void visit(MessageId m, BdfDictionary metadata)
			throws DbException, FormatException;
}
