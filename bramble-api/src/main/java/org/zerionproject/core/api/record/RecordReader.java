package org.zerionproject.core.api.record;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.Predicate;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface RecordReader {

	Record readRecord() throws IOException;

	@Nullable
	Record readRecord(RecordPredicate accept, RecordPredicate ignore)
			throws IOException;

	void close() throws IOException;

	interface RecordPredicate extends Predicate<Record> {
	}
}
