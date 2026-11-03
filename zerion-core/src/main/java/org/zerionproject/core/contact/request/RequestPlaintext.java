package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.core.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FORMAT;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_REQUEST;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_BODY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_ID_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAC_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_NAME_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_NOTE_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_NOTE_CODE_POINTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PLAINTEXT_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REQUEST_ID_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SENDER_TAG_BYTES;

@Immutable
@NotNullByDefault
final class RequestPlaintext {

	final int kind;
	final byte[] requestId, linkId;
	@Nullable
	final byte[] oneTimeLink, pop, senderTag;
	@Nullable
	final String name, note;
	final long timestamp;
	final boolean reconnect;

	private RequestPlaintext(int kind, byte[] requestId, byte[] linkId,
			@Nullable byte[] oneTimeLink, @Nullable byte[] pop,
			@Nullable String name, @Nullable String note, long timestamp,
			@Nullable byte[] senderTag, boolean reconnect) {
		this.reconnect = reconnect;
		this.kind = kind;
		this.requestId = requestId;
		this.linkId = linkId;
		this.oneTimeLink = oneTimeLink;
		this.pop = pop;
		this.name = name;
		this.note = note;
		this.timestamp = timestamp;
		this.senderTag = senderTag;
	}

	static RequestPlaintext request(byte[] requestId, byte[] linkId,
			byte[] oneTimeLink, byte[] pop, String name, @Nullable String note,
			long timestamp, byte[] senderTag) {
		return request(requestId, linkId, oneTimeLink, pop, name, note,
				timestamp, senderTag, false);
	}

	/**
	 * A request; reconnect says that the sender is already a contact of the
	 * receiver and wants to replace the keys of that contact.
	 */
	static RequestPlaintext request(byte[] requestId, byte[] linkId,
			byte[] oneTimeLink, byte[] pop, String name, @Nullable String note,
			long timestamp, byte[] senderTag, boolean reconnect) {
		RequestPlaintext p = new RequestPlaintext(KIND_REQUEST, requestId,
				linkId, oneTimeLink, pop, name, note, timestamp, senderTag,
				reconnect);
		p.check();
		return p;
	}

	static RequestPlaintext withdraw(byte[] requestId, byte[] linkId) {
		return new RequestPlaintext(KIND_WITHDRAW, requestId, linkId, null,
				null, null, null, 0, null, false);
	}

	static boolean isValidName(String name) {
		int n = StringUtils.toUtf8(name).length;
		return n >= 1 && n <= MAX_NAME_BYTES;
	}

	static boolean isValidNote(@Nullable String note) {
		if (note == null) return true;
		int points = note.codePointCount(0, note.length());
		return points >= 1 && points <= MAX_NOTE_CODE_POINTS
				&& StringUtils.toUtf8(note).length <= MAX_NOTE_BYTES;
	}

	private void check() {
		if (requestId.length != REQUEST_ID_BYTES
				|| linkId.length != LINK_ID_BYTES) {
			throw new IllegalArgumentException();
		}
		if (kind == KIND_REQUEST) {
			if (oneTimeLink == null || oneTimeLink.length != LINK_BODY_BYTES
					|| (oneTimeLink[0] & 0xFF) != LINK_TYPE_ZERO_ID
					|| pop == null || pop.length != MAC_BYTES
					|| name == null || !isValidName(name)
					|| !isValidNote(note) || timestamp < 0
					|| senderTag == null
					|| senderTag.length != SENDER_TAG_BYTES) {
				throw new IllegalArgumentException();
			}
		}
	}

	byte[] encode(BdfWriterFactory writerFactory) {
		BdfList list = BdfList.of(FORMAT, kind, requestId, linkId);
		if (kind == KIND_REQUEST) {
			list.add(oneTimeLink);
			list.add(pop);
			list.add(name);
			list.add(note);
			list.add(timestamp);
			list.add(senderTag);
			list.add(reconnect);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeList(list);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		byte[] bdf = out.toByteArray();
		if (bdf.length > PLAINTEXT_BYTES - 2) {
			throw new IllegalArgumentException();
		}
		byte[] padded = new byte[PLAINTEXT_BYTES];
		ByteUtils.writeUint16(bdf.length, padded, 0);
		System.arraycopy(bdf, 0, padded, 2, bdf.length);
		return padded;
	}

	static RequestPlaintext decode(BdfReaderFactory readerFactory,
			byte[] padded) throws FormatException {
		if (padded.length != PLAINTEXT_BYTES) throw new FormatException();
		int length = ByteUtils.readUint16(padded, 0);
		if (length > PLAINTEXT_BYTES - 2) throw new FormatException();
		for (int i = 2 + length; i < PLAINTEXT_BYTES; i++) {
			if (padded[i] != 0) throw new FormatException();
		}
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(padded, 2, length),
				BdfReader.DEFAULT_NESTED_LIMIT, length, true);
		BdfList list;
		try {
			list = r.readList();
			if (!r.eof()) throw new FormatException();
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new FormatException();
		}
		if (list.size() < 4) throw new FormatException();
		if (list.getLong(0) != FORMAT) throw new FormatException();
		long kind = list.getLong(1);
		byte[] requestId = list.getRaw(2);
		byte[] linkId = list.getRaw(3);
		if (requestId.length != REQUEST_ID_BYTES
				|| linkId.length != LINK_ID_BYTES) {
			throw new FormatException();
		}
		if (kind == KIND_WITHDRAW) {
			if (list.size() != 4) throw new FormatException();
			return withdraw(requestId, linkId);
		}
		if (kind != KIND_REQUEST || list.size() != 11) {
			throw new FormatException();
		}
		byte[] oneTimeLink = list.getRaw(4);
		byte[] pop = list.getRaw(5);
		String name = list.getString(6);
		String note = list.getOptionalString(7);
		long timestamp = list.getLong(8);
		byte[] senderTag = list.getRaw(9);
		boolean reconnect = list.getBoolean(10);
		try {
			return request(requestId, linkId, oneTimeLink, pop, name, note,
					timestamp, senderTag, reconnect);
		} catch (IllegalArgumentException e) {
			throw new FormatException();
		}
	}
}
