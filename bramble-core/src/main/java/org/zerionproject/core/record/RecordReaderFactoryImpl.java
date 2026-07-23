package org.zerionproject.core.record;

import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReaderFactory;

import java.io.InputStream;

class RecordReaderFactoryImpl implements RecordReaderFactory {

	@Override
	public RecordReader createRecordReader(InputStream in) {
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
