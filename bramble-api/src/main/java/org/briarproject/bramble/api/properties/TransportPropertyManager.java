package org.briarproject.bramble.api.properties;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;

@NotNullByDefault
public interface TransportPropertyManager {

	
	ClientId CLIENT_ID = new ClientId("org.briarproject.bramble.properties");

	
	int MAJOR_VERSION = 0;

	
	int MINOR_VERSION = 0;

	
	void addRemoteProperties(Transaction txn, ContactId c,
			Map<TransportId, TransportProperties> props) throws DbException;

	
	void addRemotePropertiesFromConnection(ContactId c, TransportId t,
			TransportProperties props) throws DbException;

	
	Map<TransportId, TransportProperties> getLocalProperties()
			throws DbException;

	
	Map<TransportId, TransportProperties> getLocalProperties(Transaction txn)
			throws DbException;

	
	TransportProperties getLocalProperties(TransportId t) throws DbException;

	
	Map<ContactId, TransportProperties> getRemoteProperties(TransportId t)
			throws DbException;

	
	TransportProperties getRemoteProperties(ContactId c, TransportId t)
			throws DbException;

	
	void mergeLocalProperties(TransportId t, TransportProperties p)
			throws DbException;
}
