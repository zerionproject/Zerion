package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@NotNullByDefault
public interface SyncRecordReaderFactory {

	/**
	 * Creates a sync record reader using the default (extended) format.
	 */
	SyncRecordReader createRecordReader(InputStream in);

	/**
	 * Creates a sync record reader using the specified format.
	 *
	 * @param classical true for Briar-compatible 4-byte header format,
	 *                  false for extended 6-byte header format
	 */
	SyncRecordReader createRecordReader(InputStream in, boolean classical);
}
