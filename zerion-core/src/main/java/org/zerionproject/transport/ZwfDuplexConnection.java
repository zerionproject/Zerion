package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.crypto.ZwfMode3FullStreamDecrypter;
import org.zerionproject.crypto.ZwfMode3FullStreamEncrypter;
import org.zerionproject.crypto.ZwfTag;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.wire.ZwfStreamCounter;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

/**
 * A duplex ZWF connection to one contact, built on a completed handshake's
 * {@link ZwfSession}. It owns one long-lived outgoing stream (encrypter) and one
 * incoming stream (decrypter). Each application message is one frame.
 *
 * <p>Outgoing: a persistent {@code streamId} is allocated (durably, before use),
 * its tag is written, and messages are framed under the send-side Mode3Full
 * ratchet. Incoming: the peer's tag is recognised to a {@code (contact,
 * streamId)}, the stream id is accepted against replay, and frames are opened
 * under the receive-side ratchet.
 *
 * <p><strong>Post-quantum engagement:</strong> a fresh session's first message
 * per direction is the zero-ciphertext sentinel (classical), after which each
 * side advertises its ML-KEM key in-band. Send and receive share one Mode3Full state (via sharedM3f under directionLock),
 * so the peer key learned while receiving is fed into the send side and
 * per-message ML-KEM engages from the second message onward.
 * currentMode3FullState() exposes it so callers/tests can confirm engagement.
 */
@NotNullByDefault
public class ZwfDuplexConnection {

	private final int contactId;
	private final ZwfSession session;
	private final ZwfStreamCounter counter;
	private final CryptoComponent crypto;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final Supplier<AuthenticatedCipher> cipherFactory;
	private final OutputStream out;
	private final BufferedInputStream in;
	private final ZwfTagRecogniser recogniser;
	// Shared across both directions; access serialised by the lock.
	private final java.util.concurrent.atomic.AtomicReference<
			org.zerionproject.core.api.crypto.pcs.Mode3FullState> sharedM3f;
	private final java.util.concurrent.locks.Lock directionLock =
			new java.util.concurrent.locks.ReentrantLock();

	private ZwfMode3FullStreamEncrypter encrypter;
	private ZwfMode3FullStreamDecrypter decrypter;
	private long pendingStreamId;
	private boolean recvStreamCommitted;

	public ZwfDuplexConnection(int contactId, ZwfSession session,
			ZwfStreamCounter counter, CryptoComponent crypto, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet,
			Supplier<AuthenticatedCipher> cipherFactory, InputStream in,
			OutputStream out) {
		this.contactId = contactId;
		this.session = session;
		this.counter = counter;
		this.crypto = crypto;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.cipherFactory = cipherFactory;
		this.out = out;
		this.in = new BufferedInputStream(in);
		this.recogniser = new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		long recvHighWater = counter.currentRecvHighWater(contactId);
		this.recogniser.register(contactId, session.getRecvTagKey(),
				recvHighWater);
		this.sharedM3f = new java.util.concurrent.atomic.AtomicReference<>(
				session.getSendState().getMode3FullState());
	}

	/**
	 * The largest payload {@link #sendMessage} accepts in one frame. A larger
	 * application record must be fragmented (see the ZMM fragmenter) before it is
	 * sent.
	 */
	public int getMaxMessageLength() {
		return ZwfMode3FullStreamEncrypter.maxMessageLength();
	}

	/** Sends one application message as a frame on the outgoing stream. */
	public void sendMessage(byte[] payload) throws IOException {
		if (encrypter == null) {
			long streamId = counter.allocateSendStreamId(contactId);
			byte[] tag = ZwfTag.computeTag(crypto, session.getSendTagKey(),
					streamId);
			byte[] streamHeaderNonce = new byte[NONCE_LENGTH];
			crypto.getSecureRandom().nextBytes(streamHeaderNonce);
			encrypter = new ZwfMode3FullStreamEncrypter(out, cipherFactory.get(),
					ratchet, mode3FullRatchet, streamId, tag, streamHeaderNonce,
					session.getSendHeaderKey(), session.getSendState(), null,
					sharedM3f::get, sharedM3f::set, directionLock,
					session.isAlice());
		}
		encrypter.writeFrame(payload, payload.length, false);
	}

	/**
	 * Receives one application message, or {@code null} at end of stream.
	 * Recognises the incoming stream on the first call.
	 */
	public byte[] receiveMessage() throws IOException {
		if (decrypter == null) {
			byte[] tag = peekTag();
			ZwfTagRecogniser.Match match = recogniser.recognise(tag);
			if (match == null) {
				throw new FormatException();
			}
			pendingStreamId = match.streamId;
			decrypter = new ZwfMode3FullStreamDecrypter(in, cipherFactory.get(),
					ratchet, mode3FullRatchet, null, tag, match.streamId,
					session.getRecvHeaderKey(), session.getRecvState(), null,
					sharedM3f::get, sharedM3f::set, directionLock,
					!session.isAlice());
		}
		byte[] buf = new byte[FRAME_LENGTH];
		int n;
		try {
			n = decrypter.readFrame(buf);
		} catch (FormatException fe) {
			throw fe;
		}
		if (!recvStreamCommitted) {
			// Commit the stream id only after the first frame authenticates, so an unauthenticated stream cannot slide the window.
			if (!counter.acceptRecvStreamId(contactId, pendingStreamId)) {
				throw new FormatException();
			}
			recogniser.advanceTo(contactId, pendingStreamId);
			recvStreamCommitted = true;
		}
		if (n < 0) return null;
		return Arrays.copyOf(buf, n);
	}

	/**
	 * The current shared Mode3Full state. Once each side has learned the peer's
	 * advertised ML-KEM key, {@code getTheirActivePqPk()} is non-null and
	 * subsequent sends engage per-message post-quantum encapsulation.
	 */
	public org.zerionproject.core.api.crypto.pcs.Mode3FullState
			currentMode3FullState() {
		return sharedM3f.get();
	}

	private byte[] peekTag() throws IOException {
		in.mark(TAG_LENGTH);
		byte[] tag = new byte[TAG_LENGTH];
		int off = 0;
		while (off < TAG_LENGTH) {
			int r = in.read(tag, off, TAG_LENGTH - off);
			if (r == -1) throw new EOFException();
			off += r;
		}
		in.reset();
		return tag;
	}
}
