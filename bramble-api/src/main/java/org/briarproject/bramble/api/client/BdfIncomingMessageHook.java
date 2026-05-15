package org.briarproject.bramble.api.client;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook;
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
