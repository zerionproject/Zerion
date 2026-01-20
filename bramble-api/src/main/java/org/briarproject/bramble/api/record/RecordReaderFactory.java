package org.briarproject.bramble.api.record;

import java.io.InputStream;

public interface RecordReaderFactory {

	/**
	 * Creates a record reader using the extended (post-quantum) format.
	 * This is the default for Zerion-to-Zerion communication.
	 */
	RecordReader createRecordReader(InputStream in);

	/**
	 * Creates a record reader using the specified format.
	 *
	 * @param in the input stream to read from
	 * @param classical if true, use classical (Briar-compatible) 4-byte header
	 *                  with uint16 length; if false, use extended 6-byte header
	 *                  with uint32 length
	 */
	RecordReader createRecordReader(InputStream in, boolean classical);
}
