package org.zerionproject.core.sync;

import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
class SyncRecordReaderFactoryImpl implements SyncRecordReaderFactory {

	private final MessageFactory messageFactory;
	private final RecordReaderFactory recordReaderFactory;

	@Inject
	SyncRecordReaderFactoryImpl(MessageFactory messageFactory,
			RecordReaderFactory recordReaderFactory) {
		this.messageFactory = messageFactory;
		this.recordReaderFactory = recordReaderFactory;
	}

	@Override
	public SyncRecordReader createRecordReader(InputStream in) {
		RecordReader reader = recordReaderFactory.createRecordReader(in);
		return new SyncRecordReaderImpl(messageFactory, reader);
	}

	@Override
	public SyncRecordReader createRecordReader(InputStream in, boolean classical) {
		RecordReader reader = recordReaderFactory.createRecordReader(in, classical);
		return new SyncRecordReaderImpl(messageFactory, reader);
	}
}
