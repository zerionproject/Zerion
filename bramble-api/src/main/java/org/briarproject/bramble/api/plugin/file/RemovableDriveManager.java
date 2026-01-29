package org.briarproject.bramble.api.plugin.file;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface RemovableDriveManager {

	
	@Nullable
	RemovableDriveTask getCurrentReaderTask();

	
	@Nullable
	RemovableDriveTask getCurrentWriterTask();

	
	RemovableDriveTask startReaderTask(TransportProperties p);

	
	RemovableDriveTask startWriterTask(ContactId c, TransportProperties p);

	
	boolean isTransportSupportedByContact(ContactId c) throws DbException;

	
	boolean isWriterTaskNeeded(ContactId c) throws DbException;
}
