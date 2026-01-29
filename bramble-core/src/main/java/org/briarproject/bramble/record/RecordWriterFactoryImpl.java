package org.briarproject.bramble.record;

import org.briarproject.bramble.api.record.RecordWriter;
import org.briarproject.bramble.api.record.RecordWriterFactory;

import java.io.OutputStream;

class RecordWriterFactoryImpl implements RecordWriterFactory {

	@Override
	public RecordWriter createRecordWriter(OutputStream out) {
		return new RecordWriterImpl(out);
	}

	@Override
	public RecordWriter createRecordWriter(OutputStream out, boolean classical) {
		if (classical) {
			return new ClassicalRecordWriterImpl(out);
		} else {
			return new RecordWriterImpl(out);
		}
	}
}
