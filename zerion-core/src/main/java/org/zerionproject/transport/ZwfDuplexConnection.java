package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

@NotNullByDefault
public class ZwfDuplexConnection {

	private final int contactId;
	private final long generation;
	private final ZwfSession session;
	private final ZwfStreamCounter counter;
	private final CryptoComponent crypto;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final Supplier<AuthenticatedCipher> cipherFactory;
	private final OutputStream out;
	private final BufferedInputStream in;
	private final ZwfTagRecogniser recogniser;
	private final AtomicReference<Mode3FullState> sharedM3f;
	private final Lock directionLock = new ReentrantLock();

	static final long MAX_RECV_STREAM_GAP = 1L << 16;

	@Nullable
	private ZwfMode3FullStreamEncrypter encrypter;
	@Nullable
	private ZwfMode3FullStreamDecrypter decrypter;
	private long recvEpoch = -1;
	private boolean recvStreamCommitted;
	private boolean firstFrameReported;
	@Nullable
	private volatile LongConsumer firstFrameListener;
	@Nullable
	private volatile ZwfControlHandler controlHandler;
	private final Object sendLock = new Object();
	private boolean destroyed = false;
	private final OutputStream rawOut;
	private final InputStream rawIn;

	public ZwfDuplexConnection(int contactId, ZwfSession session,
			ZwfStreamCounter counter, CryptoComponent crypto, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet,
			Supplier<AuthenticatedCipher> cipherFactory, InputStream in,
			OutputStream out) {
		this(contactId, counter.generation(contactId), session, counter, crypto,
				ratchet, mode3FullRatchet, cipherFactory, in, out);
	}

	public ZwfDuplexConnection(int contactId, long generation,
			ZwfSession session, ZwfStreamCounter counter, CryptoComponent crypto,
			PcsRatchet ratchet, Mode3FullRatchet mode3FullRatchet,
			Supplier<AuthenticatedCipher> cipherFactory, InputStream in,
			OutputStream out) {
		this.contactId = contactId;
		this.generation = generation;
		this.session = session;
		this.counter = counter;
		this.crypto = crypto;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.cipherFactory = cipherFactory;
		this.out = out;
		this.rawOut = out;
		this.rawIn = in;
		this.in = new BufferedInputStream(in);
		this.recogniser = new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		Map<Long, SecretKey> tagKeys = new LinkedHashMap<>();
		for (ZwfSession.RecvKeys r : session.getRecvKeys()) {
			tagKeys.put(r.getEpoch(), r.getTagKey());
		}
		long recvHighWater = counter.currentRecvHighWater(contactId);
		this.recogniser.register(contactId, tagKeys, recvHighWater);
		this.sharedM3f = new AtomicReference<>(
				session.getSendState().getMode3FullState());
	}

	public void setFirstFrameListener(@Nullable LongConsumer listener) {
		firstFrameListener = listener;
	}

	public void setControlHandler(@Nullable ZwfControlHandler handler) {
		controlHandler = handler;
	}

	@Nullable
	public ZwfControlHandler getControlHandler() {
		return controlHandler;
	}

	public int getMaxMessageLength() {
		return ZwfMode3FullStreamEncrypter.maxMessageLength();
	}

	public long getSendEpoch() {
		return session.getSendEpoch();
	}

	public void sendMessage(byte[] payload) throws IOException {
		long streamId = -1;
		if (encrypter == null) {
			try {
				streamId = counter.allocateSendStreamId(contactId, generation);
			} catch (IllegalStateException e) {
				throw new IOException(e);
			}
		}
		synchronized (sendLock) {
			if (destroyed) throw new IOException("Connection closed");
			if (encrypter == null) {
				byte[] tag = ZwfTag.computeTag(crypto, session.getSendTagKey(),
						streamId);
				byte[] streamHeaderNonce = new byte[NONCE_LENGTH];
				crypto.getSecureRandom().nextBytes(streamHeaderNonce);
				encrypter = new ZwfMode3FullStreamEncrypter(out,
						cipherFactory.get(), ratchet, mode3FullRatchet,
						streamId, tag, streamHeaderNonce,
						session.getSendHeaderKey(), session.getSendState(), null,
						sharedM3f::get, sharedM3f::set, directionLock,
						session.isAlice());
			}
			encrypter.writeFrame(payload, payload.length, false);
		}
	}

	public void closeStreams() {
		try {
			rawOut.close();
		} catch (IOException ignored) {
		}
		try {
			rawIn.close();
		} catch (IOException ignored) {
		}
	}

	public boolean isDestroyed() {
		synchronized (sendLock) {
			return destroyed;
		}
	}

	public byte[] receiveMessage() throws IOException {
		ZwfMode3FullStreamDecrypter d = decrypter;
		if (d == null) {
			byte[] tag = peekTag();
			ZwfTagRecogniser.Match match = recogniser.recognise(tag);
			if (match == null) {
				match = recogniser.recogniseBeyondWindow(contactId, tag,
						MAX_RECV_STREAM_GAP);
			}
			if (match == null) {
				throw new FormatException();
			}
			ZwfSession.RecvKeys keys = session.getRecvKeys(match.epoch);
			if (keys == null) throw new FormatException();
			recvEpoch = match.epoch;
			long expected = match.streamId;
			d = new ZwfMode3FullStreamDecrypter(in, cipherFactory.get(),
					ratchet, mode3FullRatchet, tag, expected,
					keys.getHeaderKey(), keys.getState(), null,
					sharedM3f::get, sharedM3f::set, directionLock,
					!session.isAlice());
			d.setStreamIdAcceptor(this::commitRecvStreamId);
			decrypter = d;
		}
		byte[] buf = new byte[FRAME_LENGTH];
		int n = d.readFrame(buf);
		if (!recvStreamCommitted) throw new FormatException();
		try {
			if (n < 0) return null;
			if (!firstFrameReported) {
				firstFrameReported = true;
				LongConsumer l = firstFrameListener;
				if (l != null) l.accept(recvEpoch);
				ZwfControlHandler h = controlHandler;
				if (h != null) h.onPeerStreamAuthenticated(recvEpoch);
			}
			return Arrays.copyOf(buf, n);
		} finally {
			Arrays.fill(buf, (byte) 0);
		}
	}

	private boolean commitRecvStreamId(long streamId) {
		if (recvStreamCommitted) return true;
		if (!counter.acceptRecvStreamId(contactId, streamId, generation)) {
			return false;
		}
		recogniser.advanceTo(contactId, streamId);
		recvStreamCommitted = true;
		return true;
	}

	public boolean lastFrameCarriedPqSecret() {
		ZwfMode3FullStreamDecrypter d = decrypter;
		return d != null && d.lastFrameCarriedPqSecret();
	}

	public Mode3FullState currentMode3FullState() {
		return sharedM3f.get();
	}

	public void destroyKeyMaterial() {
		synchronized (sendLock) {
			destroyed = true;
			Mode3FullState s = sharedM3f.get();
			if (s != null) s.destroy();
			ZwfMode3FullStreamEncrypter e = encrypter;
			if (e != null) e.destroy();
			ZwfMode3FullStreamDecrypter d = decrypter;
			if (d != null) d.destroy();
			session.clear();
		}
	}

	public boolean isPqReady() {
		Mode3FullState s = sharedM3f.get();
		return s != null && s.getTheirActivePqPk() != null;
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
