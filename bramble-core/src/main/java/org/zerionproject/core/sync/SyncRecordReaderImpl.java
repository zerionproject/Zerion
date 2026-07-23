package org.zerionproject.core.sync;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UniqueId;
import org.zerionproject.core.api.record.Record;
import org.zerionproject.core.api.record.RecordReader;
import org.zerionproject.core.api.record.RecordReader.RecordPredicate;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.Versions;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.UniqueId.LENGTH;
import static org.zerionproject.core.api.sync.RecordTypes.ACK;
import static org.zerionproject.core.api.sync.RecordTypes.MESSAGE;
import static org.zerionproject.core.api.sync.RecordTypes.MESSAGE_FRAGMENT;
import static org.zerionproject.core.api.sync.RecordTypes.OFFER;
import static org.zerionproject.core.api.sync.RecordTypes.PRIORITY;
import static org.zerionproject.core.api.sync.RecordTypes.REQUEST;
import static org.zerionproject.core.api.sync.RecordTypes.VERSIONS;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_SUPPORTED_VERSIONS;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.PRIORITY_NONCE_BYTES;
import static org.zerionproject.core.api.sync.SyncConstants.PROTOCOL_VERSION;

@NotThreadSafe
@NotNullByDefault
class SyncRecordReaderImpl implements SyncRecordReader {
	private static final RecordPredicate ACCEPT = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					isKnownRecordType(r.getRecordType());
	private static final RecordPredicate IGNORE = r ->
			r.getProtocolVersion() == PROTOCOL_VERSION &&
					!isKnownRecordType(r.getRecordType());

	private static boolean isKnownRecordType(byte type) {
		return type == ACK || type == MESSAGE || type == OFFER ||
				type == REQUEST || type == VERSIONS || type == PRIORITY
				|| type == MESSAGE_FRAGMENT;
	}

	private static final int FRAGMENT_HEADER_LEN = LENGTH + 4;
	private static final int MAX_REASSEMBLY_IN_FLIGHT = 32;

	private final MessageFactory messageFactory;
	private final RecordReader reader;

	@Nullable
	private Record nextRecord = null;
	private boolean eof = false;
	private final java.util.LinkedHashMap<MessageId, FragmentBuffer>
			reassembly = new java.util.LinkedHashMap<>();

	private static final class FragmentBuffer {
		final byte[][] chunks;
		final int total;
		int received;

		FragmentBuffer(int total) {
			this.chunks = new byte[total][];
			this.total = total;
		}
	}

	SyncRecordReaderImpl(MessageFactory messageFactory, RecordReader reader) {
		this.messageFactory = messageFactory;
		this.reader = reader;
	}

	private byte getNextRecordType() {
		if (nextRecord == null) throw new AssertionError();
		return nextRecord.getRecordType();
	}

	@Override
	public boolean eof() throws IOException {
		if (nextRecord != null) return false;
		if (eof) return true;
		while (true) {
			Record r = reader.readRecord(ACCEPT, IGNORE);
			if (r == null) {
				eof = true;
				return true;
			}
			if (r.getRecordType() == MESSAGE_FRAGMENT) {
				Record reassembled = consumeFragment(r);
				if (reassembled == null) continue;
				nextRecord = reassembled;
				return false;
			}
			nextRecord = r;
			return false;
		}
	}

	@Nullable
	private Record consumeFragment(Record r) throws FormatException {
		byte[] p = r.getPayload();
		if (p.length < FRAGMENT_HEADER_LEN) throw new FormatException();
		byte[] idBytes = new byte[LENGTH];
		System.arraycopy(p, 0, idBytes, 0, LENGTH);
		MessageId id = new MessageId(idBytes);
		int idx = ((p[LENGTH] & 0xFF) << 8) | (p[LENGTH + 1] & 0xFF);
		int total = ((p[LENGTH + 2] & 0xFF) << 8)
				| (p[LENGTH + 3] & 0xFF);
		if (total == 0 || idx >= total) throw new FormatException();
		int chunkLen = p.length - FRAGMENT_HEADER_LEN;
		if (chunkLen == 0) throw new FormatException();
		if ((long) total * chunkLen > MAX_MESSAGE_LENGTH + chunkLen) {
			throw new FormatException();
		}
		FragmentBuffer fb = reassembly.get(id);
		if (fb == null) {
			if (reassembly.size() >= MAX_REASSEMBLY_IN_FLIGHT) {
				java.util.Iterator<java.util.Map.Entry<MessageId,
						FragmentBuffer>> it =
						reassembly.entrySet().iterator();
				if (it.hasNext()) {
					it.next();
					it.remove();
				}
			}
			fb = new FragmentBuffer(total);
			reassembly.put(id, fb);
		} else if (fb.total != total) {
			throw new FormatException();
		}
		if (fb.chunks[idx] != null) return null;
		byte[] chunk = new byte[chunkLen];
		System.arraycopy(p, FRAGMENT_HEADER_LEN, chunk, 0, chunkLen);
		fb.chunks[idx] = chunk;
		fb.received++;
		if (fb.received < fb.total) return null;
		int totalLen = 0;
		for (byte[] c : fb.chunks) totalLen += c.length;
		if (totalLen > MAX_MESSAGE_LENGTH) {
			reassembly.remove(id);
			throw new FormatException();
		}
		byte[] full = new byte[totalLen];
		int off = 0;
		for (byte[] c : fb.chunks) {
			System.arraycopy(c, 0, full, off, c.length);
			off += c.length;
		}
		reassembly.remove(id);
		return new Record(PROTOCOL_VERSION, MESSAGE, full);
	}

	@Override
	public boolean hasAck() throws IOException {
		return !eof() && getNextRecordType() == ACK;
	}

	@Override
	public Ack readAck() throws IOException {
		if (!hasAck()) throw new FormatException();
		return new Ack(readMessageIds());
	}

	private List<MessageId> readMessageIds() throws IOException {
		if (nextRecord == null) throw new AssertionError();
		byte[] payload = nextRecord.getPayload();
		if (payload.length == 0) throw new FormatException();
		if (payload.length % UniqueId.LENGTH != 0) throw new FormatException();
		List<MessageId> ids = new ArrayList<>(payload.length / UniqueId.LENGTH);
		for (int off = 0; off < payload.length; off += UniqueId.LENGTH) {
			byte[] id = new byte[UniqueId.LENGTH];
			System.arraycopy(payload, off, id, 0, UniqueId.LENGTH);
			ids.add(new MessageId(id));
		}
		nextRecord = null;
		return ids;
	}

	@Override
	public boolean hasMessage() throws IOException {
		return !eof() && getNextRecordType() == MESSAGE;
	}

	@Override
	public Message readMessage() throws IOException {
		if (!hasMessage()) throw new FormatException();
		if (nextRecord == null) throw new AssertionError();
		byte[] payload = nextRecord.getPayload();
		if (payload.length <= MESSAGE_HEADER_LENGTH)
			throw new FormatException();
		if (payload.length > MAX_MESSAGE_LENGTH)
			throw new FormatException();
		long timestamp = ByteUtils.readUint64(payload, UniqueId.LENGTH);
		if (timestamp < 0) throw new FormatException();
		nextRecord = null;
		return messageFactory.createMessage(payload);
	}

	@Override
	public boolean hasOffer() throws IOException {
		return !eof() && getNextRecordType() == OFFER;
	}

	@Override
	public Offer readOffer() throws IOException {
		if (!hasOffer()) throw new FormatException();
		return new Offer(readMessageIds());
	}

	@Override
	public boolean hasRequest() throws IOException {
		return !eof() && getNextRecordType() == REQUEST;
	}

	@Override
	public Request readRequest() throws IOException {
		if (!hasRequest()) throw new FormatException();
		return new Request(readMessageIds());
	}

	@Override
	public boolean hasVersions() throws IOException {
		return !eof() && getNextRecordType() == VERSIONS;
	}

	@Override
	public Versions readVersions() throws IOException {
		if (!hasVersions()) throw new FormatException();
		return new Versions(readSupportedVersions());
	}

	private List<Byte> readSupportedVersions() throws IOException {
		if (nextRecord == null) throw new AssertionError();
		byte[] payload = nextRecord.getPayload();
		if (payload.length == 0) throw new FormatException();
		if (payload.length > MAX_SUPPORTED_VERSIONS)
			throw new FormatException();
		List<Byte> supported = new ArrayList<>(payload.length);
		for (byte b : payload) supported.add(b);
		nextRecord = null;
		return supported;
	}

	@Override
	public boolean hasPriority() throws IOException {
		return !eof() && getNextRecordType() == PRIORITY;
	}

	@Override
	public Priority readPriority() throws IOException {
		if (!hasPriority()) throw new FormatException();
		return new Priority(readNonce());
	}

	private byte[] readNonce() throws IOException {
		if (nextRecord == null) throw new AssertionError();
		byte[] payload = nextRecord.getPayload();
		if (payload.length != PRIORITY_NONCE_BYTES) throw new FormatException();
		nextRecord = null;
		return payload;
	}
}
