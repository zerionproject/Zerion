package org.briarproject.bramble.record;

import org.briarproject.bramble.api.record.RecordReader;
import org.briarproject.bramble.api.record.RecordReaderFactory;

import java.io.InputStream;

class RecordReaderFactoryImpl implements RecordReaderFactory {

	@Override
	public RecordReader createRecordReader(InputStream in) {
		// Default to extended format for Zerion↔Zerion communication.
		// Classical format should be explicitly requested for Briar compatibility.
		return new RecordReaderImpl(in);
	}

	@Override
	public RecordReader createRecordReader(InputStream in, boolean classical) {
		if (classical) {
			return new ClassicalRecordReaderImpl(in);
		} else {
			return new RecordReaderImpl(in);
		}
	}
}
