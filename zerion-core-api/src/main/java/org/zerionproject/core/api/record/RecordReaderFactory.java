package org.zerionproject.core.api.record;

import java.io.InputStream;

public interface RecordReaderFactory {

	RecordReader createRecordReader(InputStream in);

	RecordReader createRecordReader(InputStream in, boolean classical);

	/**
	 * An extended-header reader that refuses any record whose payload is
	 * longer than {@code maxPayloadBytes}, for protocols whose records are
	 * small and whose peer is not yet authenticated.
	 */
	RecordReader createRecordReader(InputStream in, int maxPayloadBytes);
}
