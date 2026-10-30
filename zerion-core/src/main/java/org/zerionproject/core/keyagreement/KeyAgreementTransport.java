package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriter;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.keyagreement.RecordTypes.ABORT;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.CAPABILITY_KEY_SEPARATION;
import static org.zerionproject.core.api.keyagreement.RecordTypes.CAPABILITIES;
import static org.zerionproject.core.api.keyagreement.RecordTypes.CONFIRM;
import static org.zerionproject.core.api.keyagreement.RecordTypes.KEM_CIPHERTEXT;
import static org.zerionproject.core.api.keyagreement.RecordTypes.KEY;

@NotNullByDefault
class KeyAgreementTransport {
	private static final RecordPredicate ACCEPT = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					isKnownRecordType(r.getRecordType());
	private static final RecordPredicate IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());
	private static final RecordPredicate ACCEPT_BEFORE_KEY = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					(isKnownRecordType(r.getRecordType())
							|| r.getRecordType() == CAPABILITIES);
	private static final RecordPredicate IGNORE_BEFORE_KEY = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType())
					&& r.getRecordType() != CAPABILITIES;

	private static boolean isKnownRecordType(byte type) {
		return type == KEY || type == CONFIRM || type == ABORT
				|| type == KEM_CIPHERTEXT;
	}

	static final int MAX_RECORD_PAYLOAD_BYTES = 8192;

	private final KeyAgreementConnection kac;
	private final RecordReader reader;
	private final RecordWriter writer;
	private volatile int peerCapabilities = 0;

	KeyAgreementTransport(RecordReaderFactory recordReaderFactory,
			RecordWriterFactory recordWriterFactory, KeyAgreementConnection kac)
			throws IOException {
		this.kac = kac;
		InputStream in = kac.getConnection().getReader().getInputStream();
		reader = recordReaderFactory.createRecordReader(in,
				MAX_RECORD_PAYLOAD_BYTES);
		OutputStream out = kac.getConnection().getWriter().getOutputStream();
		writer = recordWriterFactory.createRecordWriter(out);
	}

	public DuplexTransportConnection getConnection() {
		return kac.getConnection();
	}

	public TransportId getTransportId() {
		return kac.getTransportId();
	}

	void sendKey(byte[] key) throws IOException {
		writeRecord(KEY, key);
	}

	void sendCapabilities() throws IOException {
		writeRecord(CAPABILITIES, new byte[] {CAPABILITY_KEY_SEPARATION});
	}

	byte[] receiveKey() throws AbortException {
		Record first = readAny(ACCEPT_BEFORE_KEY, IGNORE_BEFORE_KEY);
		if (first.getRecordType() == CAPABILITIES) {
			byte[] p = first.getPayload();
			peerCapabilities = p.length == 0 ? 0 : p[0] & 0xFF;
			return check(readAny(), KEY);
		}
		return check(first, KEY);
	}

	boolean peerOffersKeySeparation() {
		return (peerCapabilities & CAPABILITY_KEY_SEPARATION) != 0;
	}

	void sendKemCiphertext(byte[] ciphertext) throws IOException {
		writeRecord(KEM_CIPHERTEXT, ciphertext);
	}

	byte[] receiveKemCiphertext() throws AbortException {
		return readRecord(KEM_CIPHERTEXT);
	}

	void sendConfirm(byte[] confirm) throws IOException {
		writeRecord(CONFIRM, confirm);
	}

	byte[] receiveConfirm() throws AbortException {
		return readRecord(CONFIRM);
	}

	void sendAbort(boolean exception) {
		try {
			writeRecord(ABORT, new byte[0]);
		} catch (IOException e) {
			exception = true;
		}
		tryToClose(exception);
	}

	private void tryToClose(boolean exception) {
		try {
			kac.getConnection().getReader().dispose(exception, true);
			kac.getConnection().getWriter().dispose(exception);
		} catch (IOException e) {
		}
	}

	private void writeRecord(byte type, byte[] payload) throws IOException {
		writer.writeRecord(new Record(PROTOCOL_VERSION, type, payload));
		writer.flush();
	}

	private byte[] readRecord(byte expectedType) throws AbortException {
		return check(readAny(), expectedType);
	}

	private Record readAny() throws AbortException {
		return readAny(ACCEPT, IGNORE);
	}

	private Record readAny(RecordPredicate accept, RecordPredicate ignore)
			throws AbortException {
		try {
			Record record = reader.readRecord(accept, ignore);
			if (record == null) throw new AbortException(new EOFException());
			return record;
		} catch (IOException e) {
			throw new AbortException(e);
		}
	}

	private static byte[] check(Record record, byte expectedType)
			throws AbortException {
		byte type = record.getRecordType();
		if (type == ABORT) throw new AbortException(true);
		if (type != expectedType) throw new AbortException(false);
		return record.getPayload();
	}
}
