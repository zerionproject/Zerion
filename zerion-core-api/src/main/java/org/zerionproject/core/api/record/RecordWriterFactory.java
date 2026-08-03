package org.zerionproject.core.api.record;

import java.io.OutputStream;

public interface RecordWriterFactory {

	RecordWriter createRecordWriter(OutputStream out);

	RecordWriter createRecordWriter(OutputStream out, boolean classical);
}
