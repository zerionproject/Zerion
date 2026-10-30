package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.AgreementPrivateKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.contact.request.ContactRequestCrypto.Opened;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Record;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEALED_DEADLINE_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_ACK;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_FULL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_HELLO;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SEALED;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SLOTS_BUSY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNSOLVED_HOLD_MS;

@NotThreadSafe
@NotNullByDefault
final class InboxSession {

	interface Connection {

		InputStream getInputStream() throws IOException;

		OutputStream getOutputStream() throws IOException;

		void close();
	}

	interface Timer {

		Cancellable schedule(Runnable task, long delayMs);
	}

	interface Receiver {

		boolean isFull() throws DbException;

		@Nullable
		byte[] handle(RequestPlaintext p, SecretKey mk, byte[] helloNonce,
				long receivedAt, long admittedAt) throws DbException;
	}

	interface Admission {

		Admission OPEN = new Admission() {
			@Override
			public boolean enter(Connection c) {
				return true;
			}

			@Override
			public void leave(Connection c) {
			}
		};

		boolean enter(Connection c);

		void leave(Connection c);
	}

	static final class Inbox {

		final byte[] linkId, onionPublicKey;
		final KeyPair linkKeys;
		final InboxPuzzle puzzle;
		final InboxSlots slots;
		final Admission admission;

		Inbox(byte[] linkId, byte[] onionPublicKey, KeyPair linkKeys,
				InboxPuzzle puzzle, InboxSlots slots) {
			this(linkId, onionPublicKey, linkKeys, puzzle, slots,
					Admission.OPEN);
		}

		Inbox(byte[] linkId, byte[] onionPublicKey, KeyPair linkKeys,
				InboxPuzzle puzzle, InboxSlots slots, Admission admission) {
			this.linkId = linkId;
			this.onionPublicKey = onionPublicKey;
			this.linkKeys = linkKeys;
			this.puzzle = puzzle;
			this.slots = slots;
			this.admission = admission;
		}
	}

	private final ContactRequestCrypto crypto;
	private final BdfReaderFactory readers;
	private final Clock clock;
	private final Timer timer;
	private final Inbox inbox;
	private final Receiver receiver;

	InboxSession(ContactRequestCrypto crypto, BdfReaderFactory readers,
			Clock clock, Timer timer, Inbox inbox, Receiver receiver) {
		this.crypto = crypto;
		this.readers = readers;
		this.clock = clock;
		this.timer = timer;
		this.inbox = inbox;
		this.receiver = receiver;
	}

	void run(Connection c) {
		long start = clock.currentTimeMillis();
		Object evicted = inbox.slots.connected(c, start);
		if (evicted != null) {
			((Connection) evicted).close();
			inbox.puzzle.zeroWork(start);
		}
		Cancellable unsolvedDeadline = timer.schedule(() -> {
			Object expired =
					inbox.slots.expireUnsolved(clock.currentTimeMillis());
			if (expired != null) {
				((Connection) expired).close();
				inbox.puzzle.zeroWork(clock.currentTimeMillis());
			}
		}, UNSOLVED_HOLD_MS);
		boolean solutionRead = false;
		boolean entered = false;
		long admittedAt = 0;
		KeyPair y = null;
		SecretKey mk = null;
		try {
			InputStream in = c.getInputStream();
			OutputStream out = c.getOutputStream();
			Hello hello = inbox.puzzle.hello(start);
			ContactRequestRecords.write(out, TYPE_HELLO, hello.encode());
			Record first = ContactRequestRecords.read(in, TYPE_SOLUTION);
			if (!inbox.slots.solutionReceived(c)) return;
			unsolvedDeadline.cancel();
			solutionRead = true;
			Solution s = Solution.decode(first.payload);
			long now = clock.currentTimeMillis();
			if (!inbox.puzzle.isValid(s, hello, now)) {
				inbox.puzzle.zeroWork(now);
				return;
			}
			if (receiver.isFull()) {
				ContactRequestRecords.write(out, TYPE_FULL, new byte[0]);
				return;
			}
			if (!inbox.slots.acquireSolved(c)) {
				ContactRequestRecords.write(out, TYPE_SLOTS_BUSY,
						new byte[0]);
				return;
			}
			if (!inbox.puzzle.admit(s, now)) return;
			if (!inbox.admission.enter(c)) return;
			entered = true;
			admittedAt = now;
			y = crypto.getCrypto().generateAgreementKeyPair();
			byte[] keyPayload = ContactRequestRecords.encodeKey(
					inbox.linkKeys.getPublic().getEncoded(),
					y.getPublic().getEncoded());
			ContactRequestRecords.write(out, TYPE_KEY, keyPayload);
			Cancellable sealedDeadline =
					timer.schedule(c::close, SEALED_DEADLINE_MS);
			Record sealed = ContactRequestRecords.read(in, TYPE_SEALED);
			sealedDeadline.cancel();
			byte[] nonce = inbox.puzzle.nonce(s.epoch, s.level);
			byte[] helloHash =
					crypto.helloHash(s.epoch, s.level, nonce, keyPayload);
			Opened opened;
			try {
				opened = crypto.open(inbox.linkKeys, y, helloHash,
						inbox.onionPublicKey, inbox.linkId, sealed.payload);
			} catch (GeneralSecurityException e) {
				return;
			}
			mk = opened.mk;
			RequestPlaintext p;
			try {
				p = RequestPlaintext.decode(readers, opened.plaintext);
			} catch (FormatException e) {
				return;
			} finally {
				Arrays.fill(opened.plaintext, (byte) 0);
			}
			if (!MessageDigest.isEqual(p.linkId, inbox.linkId)) return;
			byte[] ack = receiver.handle(p, mk, nonce,
					clock.currentTimeMillis(), admittedAt);
			if (ack == null) return;
			ContactRequestRecords.write(out, TYPE_ACK, ack);
		} catch (IOException | DbException | RuntimeException e) {
			if (!solutionRead && inbox.slots.solutionReceived(c)) {
				inbox.puzzle.zeroWork(clock.currentTimeMillis());
			}
		} finally {
			unsolvedDeadline.cancel();
			if (y != null && y.getPrivate() instanceof AgreementPrivateKey) {
				Arrays.fill(((AgreementPrivateKey) y.getPrivate()).getBytes(),
						(byte) 0);
			}
			if (mk != null) mk.clear();
			inbox.slots.closed(c);
			c.close();
			if (entered) inbox.admission.leave(c);
		}
	}
}
