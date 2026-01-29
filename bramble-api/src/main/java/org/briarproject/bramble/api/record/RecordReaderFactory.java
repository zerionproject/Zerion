package org.briarproject.bramble.api.record;

import java.io.InputStream;

public interface RecordReaderFactory {

	
	RecordReader createRecordReader(InputStream in);

	
	RecordReader createRecordReader(InputStream in, boolean classical);
}
