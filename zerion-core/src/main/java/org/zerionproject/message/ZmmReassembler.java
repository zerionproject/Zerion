package org.zerionproject.message;

import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.crypto.ZwfMode3FullStreamEncrypter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class ZmmReassembler {

	public static final int MAX_MESSAGE_BYTES = ZmmConstants.MAX_RECORD_BYTES;
	public static final int MAX_FRAGMENTS_PER_MESSAGE = 2048;
	public static final int MAX_PARTIAL_MESSAGES_PER_CONTACT = 16;
	public static final int MAX_TOTAL_BUFFERED_BYTES = 16 * 1024 * 1024;

	public static final int MAX_BUFFERED_BYTES_PER_CONTACT =
			2 * MAX_MESSAGE_BYTES;

	public static final int MAX_CHUNK_BYTES =
			ZwfMode3FullStreamEncrypter.maxMessageLength()
					- ZmmFragmenter.RECORD_TYPE_LENGTH
					- ZmmFragmenter.FRAGMENT_HEADER_LENGTH;

	public static final long PARTIAL_IDLE_TIMEOUT_MS = 5 * 60_000L;

	public static final long SINGLE_SESSION = 0L;

	public static final class Message {
		public final int type;
		public final byte[] payload;

		Message(int type, byte[] payload) {
			this.type = type;
			this.payload = payload;
		}
	}

	private static class Partial {
		final int contactId;
		final long sessionId;
		final long messageId;
		final int type;
		final int count;
		final Map<Integer, byte[]> chunks = new HashMap<>();
		int totalBytes;
		long lastActivityMs;
		long lastTouch;

		Partial(int contactId, long sessionId, long messageId, int type,
				int count, long now, long touch) {
			this.contactId = contactId;
			this.sessionId = sessionId;
			this.messageId = messageId;
			this.type = type;
			this.count = count;
			this.lastActivityMs = now;
			this.lastTouch = touch;
		}
	}

	private final Clock clock;
	private final Object lock = new Object();
	@GuardedBy("lock")
	private final Map<Long, Map<Long, Partial>> partialsBySession =
			new HashMap<>();
	@GuardedBy("lock")
	private final Map<Integer, Integer> partialsPerContact = new HashMap<>();
	@GuardedBy("lock")
	private final Map<Integer, Long> bytesPerContact = new HashMap<>();
	@GuardedBy("lock")
	private long totalBufferedBytes;
	@GuardedBy("lock")
	private long touches;

	public ZmmReassembler() {
		this(new SystemClock());
	}

	ZmmReassembler(Clock clock) {
		this.clock = clock;
	}

	@Nullable
	public Message receive(int contactId, int type, byte[] payload) {
		return receive(contactId, SINGLE_SESSION, type, payload);
	}

	@Nullable
	public Message receive(int contactId, long sessionId, int type,
			byte[] payload) {
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
		if (chunkLen == 0 || chunkLen > MAX_CHUNK_BYTES) return null;

		synchronized (lock) {
			long now = clock.currentTimeMillis();
			evictIdle(now);
			Map<Long, Partial> session = partialsBySession.get(sessionId);
			Partial p = session == null ? null : session.get(messageId);
			if (p != null && p.contactId != contactId) return null;
			if (p == null) {
				if (partialsPerContact.getOrDefault(contactId, 0)
						>= MAX_PARTIAL_MESSAGES_PER_CONTACT) {
					return null;
				}
				p = new Partial(contactId, sessionId, messageId, origType,
						count, now, ++touches);
				if (session == null) {
					session = new HashMap<>();
					partialsBySession.put(sessionId, session);
				}
				session.put(messageId, p);
				partialsPerContact.put(contactId,
						partialsPerContact.getOrDefault(contactId, 0) + 1);
			}
			if (p.count != count || p.type != origType
					|| p.chunks.containsKey(index)) {
				return null;
			}
			if (p.totalBytes + chunkLen > MAX_MESSAGE_BYTES
					|| !makeRoom(p, chunkLen)) {
				remove(p);
				return null;
			}
			byte[] chunk = new byte[chunkLen];
			System.arraycopy(payload, ZmmFragmenter.FRAGMENT_HEADER_LENGTH,
					chunk, 0, chunkLen);
			p.chunks.put(index, chunk);
			p.totalBytes += chunkLen;
			p.lastActivityMs = now;
			p.lastTouch = ++touches;
			totalBufferedBytes += chunkLen;
			bytesPerContact.put(contactId, heldBy(contactId) + chunkLen);
			if (p.chunks.size() < p.count) return null;
			remove(p);
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

	public void sessionClosed(long sessionId) {
		synchronized (lock) {
			Map<Long, Partial> session = partialsBySession.get(sessionId);
			if (session == null) return;
			for (Partial p : new ArrayList<>(session.values())) remove(p);
		}
	}

	public void clearContact(int contactId) {
		synchronized (lock) {
			for (Partial p : allPartials()) {
				if (p.contactId == contactId) remove(p);
			}
		}
	}

	@GuardedBy("lock")
	private boolean makeRoom(Partial p, int chunkLen) {
		while (heldBy(p.contactId) + chunkLen
				> MAX_BUFFERED_BYTES_PER_CONTACT) {
			Partial victim = null;
			for (Partial q : allPartials()) {
				if (q == p || q.contactId != p.contactId) continue;
				if (victim == null || q.lastTouch < victim.lastTouch) {
					victim = q;
				}
			}
			if (victim == null) return false;
			remove(victim);
		}
		while (totalBufferedBytes + chunkLen > MAX_TOTAL_BUFFERED_BYTES) {
			Partial victim = null;
			long victimHolds = 0;
			for (Partial q : allPartials()) {
				if (q == p) continue;
				long holds = heldBy(q.contactId);
				if (victim == null || holds > victimHolds
						|| (holds == victimHolds
						&& q.lastTouch < victim.lastTouch)) {
					victim = q;
					victimHolds = holds;
				}
			}
			if (victim == null) return false;
			remove(victim);
		}
		return true;
	}

	@GuardedBy("lock")
	private long heldBy(int contactId) {
		Long held = bytesPerContact.get(contactId);
		return held == null ? 0 : held;
	}

	@GuardedBy("lock")
	private void evictIdle(long now) {
		for (Partial p : allPartials()) {
			if (now - p.lastActivityMs >= PARTIAL_IDLE_TIMEOUT_MS) remove(p);
		}
	}

	@GuardedBy("lock")
	private List<Partial> allPartials() {
		List<Partial> all = new ArrayList<>();
		for (Map<Long, Partial> session : partialsBySession.values()) {
			all.addAll(session.values());
		}
		return all;
	}

	@GuardedBy("lock")
	private void remove(Partial p) {
		Map<Long, Partial> session = partialsBySession.get(p.sessionId);
		if (session == null || session.get(p.messageId) != p) return;
		session.remove(p.messageId);
		if (session.isEmpty()) partialsBySession.remove(p.sessionId);
		totalBufferedBytes -= p.totalBytes;
		Long held = bytesPerContact.get(p.contactId);
		if (held != null) {
			if (held <= p.totalBytes) bytesPerContact.remove(p.contactId);
			else bytesPerContact.put(p.contactId, held - p.totalBytes);
		}
		Integer n = partialsPerContact.get(p.contactId);
		if (n != null) {
			if (n <= 1) partialsPerContact.remove(p.contactId);
			else partialsPerContact.put(p.contactId, n - 1);
		}
	}
}
