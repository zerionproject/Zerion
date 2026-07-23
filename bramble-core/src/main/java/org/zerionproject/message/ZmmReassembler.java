package org.zerionproject.message;

import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Reassembles records that {@link ZmmFragmenter} split across frames. Every
 * decoded record is fed in; a non-fragment record passes straight through, and a
 * fragment yields the original record only once its final piece has arrived.
 *
 * <p>Partial messages are bounded against a hostile peer: a fragment count above
 * {@link #MAX_FRAGMENTS_PER_MESSAGE} or a message over {@link #MAX_MESSAGE_BYTES}
 * is rejected, buffered fragment bytes across all contacts never exceed
 * {@link #MAX_TOTAL_BUFFERED_BYTES}, and each contact may hold at most
 * {@link #MAX_PARTIAL_MESSAGES_PER_CONTACT} incomplete messages so one contact
 * cannot starve reassembly for the others. Fragment storage is grown lazily so a
 * claimed-but-unfilled count costs nothing until the bytes actually arrive.
 * {@link #clearContact} drops a contact's partial state when its connection ends.
 */
@ThreadSafe
@NotNullByDefault
public class ZmmReassembler {

	public static final int MAX_MESSAGE_BYTES = 1024 * 1024;
	public static final int MAX_FRAGMENTS_PER_MESSAGE = 2048;
	public static final int MAX_PARTIAL_MESSAGES_PER_CONTACT = 16;
	public static final int MAX_TOTAL_BUFFERED_BYTES = 16 * 1024 * 1024;

	/** A completed record: its original type and reassembled payload. */
	public static final class Message {
		public final int type;
		public final byte[] payload;

		Message(int type, byte[] payload) {
			this.type = type;
			this.payload = payload;
		}
	}

	private static class Partial {
		final int type;
		final int count;
		// Grown lazily: a claimed count does not allocate until bytes arrive.
		final Map<Integer, byte[]> chunks = new HashMap<>();
		int totalBytes;

		Partial(int type, int count) {
			this.type = type;
			this.count = count;
		}
	}

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final Map<Long, Partial> partials = new HashMap<>();
	@GuardedBy("lock")
	private final Map<Integer, Integer> partialsPerContact = new HashMap<>();
	@GuardedBy("lock")
	private long totalBufferedBytes;

	/**
	 * Feeds one decoded record. Returns the completed message if this record was
	 * a whole record or the last fragment of one, or {@code null} if it was an
	 * incomplete or dropped fragment.
	 */
	@Nullable
	public Message receive(int contactId, int type, byte[] payload) {
		if (type != ZmmConstants.TYPE_FRAGMENT) {
			return new Message(type, payload);
		}
		if (payload.length < ZmmFragmenter.FRAGMENT_HEADER_LENGTH) return null;
		int origType = ByteUtils.readUint16(payload, 0);
		long messageId = ByteUtils.readUint32(payload, 2);
		int index = ByteUtils.readUint16(payload, 6);
		int count = ByteUtils.readUint16(payload, 8);
		if (count == 0 || count > MAX_FRAGMENTS_PER_MESSAGE || index >= count) {
			return null;
		}
		int chunkLen = payload.length - ZmmFragmenter.FRAGMENT_HEADER_LENGTH;

		long key = (((long) contactId) << 32) | (messageId & 0xFFFFFFFFL);
		synchronized (lock) {
			Partial p = partials.get(key);
			if (p == null) {
				if (partialsPerContact.getOrDefault(contactId, 0)
						>= MAX_PARTIAL_MESSAGES_PER_CONTACT) {
					return null;
				}
				p = new Partial(origType, count);
				partials.put(key, p);
				partialsPerContact.put(contactId,
						partialsPerContact.getOrDefault(contactId, 0) + 1);
			}
			if (p.count != count || p.type != origType
					|| p.chunks.containsKey(index)) {
				return null;
			}
			if (p.totalBytes + chunkLen > MAX_MESSAGE_BYTES
					|| totalBufferedBytes + chunkLen > MAX_TOTAL_BUFFERED_BYTES) {
				remove(key, contactId, p);
				return null;
			}
			byte[] chunk = new byte[chunkLen];
			System.arraycopy(payload, ZmmFragmenter.FRAGMENT_HEADER_LENGTH,
					chunk, 0, chunkLen);
			p.chunks.put(index, chunk);
			p.totalBytes += chunkLen;
			totalBufferedBytes += chunkLen;
			if (p.chunks.size() < p.count) return null;
			remove(key, contactId, p);
			byte[] full = new byte[p.totalBytes];
			int off = 0;
			for (int i = 0; i < p.count; i++) {
				byte[] c = p.chunks.get(i);
				System.arraycopy(c, 0, full, off, c.length);
				off += c.length;
			}
			return new Message(p.type, full);
		}
	}

	/** Drops all partial reassembly state for a contact (its connection ended). */
	public void clearContact(int contactId) {
		synchronized (lock) {
			Iterator<Map.Entry<Long, Partial>> it = partials.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Long, Partial> e = it.next();
				if ((int) (e.getKey() >> 32) == contactId) {
					totalBufferedBytes -= e.getValue().totalBytes;
					it.remove();
				}
			}
			partialsPerContact.remove(contactId);
		}
	}

	@GuardedBy("lock")
	private void remove(long key, int contactId, Partial p) {
		if (partials.remove(key) != null) {
			totalBufferedBytes -= p.totalBytes;
			Integer n = partialsPerContact.get(contactId);
			if (n != null) {
				if (n <= 1) partialsPerContact.remove(contactId);
				else partialsPerContact.put(contactId, n - 1);
			}
		}
	}
}
