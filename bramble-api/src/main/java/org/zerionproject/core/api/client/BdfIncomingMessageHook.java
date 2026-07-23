package org.zerionproject.core.api.client;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public abstract class BdfIncomingMessageHook implements IncomingMessageHook {

	protected final DatabaseComponent db;
	protected final ClientHelper clientHelper;
	protected final MetadataParser metadataParser;

	protected BdfIncomingMessageHook(DatabaseComponent db,
			ClientHelper clientHelper, MetadataParser metadataParser) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.metadataParser = metadataParser;
	}

	protected abstract DeliveryAction incomingMessage(Transaction txn,
			Message m, BdfList body, BdfDictionary meta)
			throws DbException, FormatException;

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			BdfList body = clientHelper.toList(m);
			BdfDictionary metaDictionary = metadataParser.parse(meta);
			return incomingMessage(txn, m, body, metaDictionary);
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
	}
}
