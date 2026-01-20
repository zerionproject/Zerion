package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;

@NotNullByDefault
public interface SyncRecordWriterFactory {

	/**
	 * Creates a sync record writer using the default (extended) format.
	 */
	SyncRecordWriter createRecordWriter(OutputStream out);

	/**
	 * Creates a sync record writer using the specified format.
	 *
	 * @param classical true for Briar-compatible 4-byte header format,
	 *                  false for extended 6-byte header format
	 */
	SyncRecordWriter createRecordWriter(OutputStream out, boolean classical);
}
