package org.briarproject.bramble.api.record;

import java.io.OutputStream;

public interface RecordWriterFactory {

	/**
	 * Creates a record writer using the extended (post-quantum) format.
	 * This is the default for Zerion-to-Zerion communication.
	 */
	RecordWriter createRecordWriter(OutputStream out);

	/**
	 * Creates a record writer using the specified format.
	 *
	 * @param out the output stream to write to
	 * @param classical if true, use classical (Briar-compatible) 4-byte header
	 *                  with uint16 length; if false, use extended 6-byte header
	 *                  with uint32 length
	 */
	RecordWriter createRecordWriter(OutputStream out, boolean classical);
}
