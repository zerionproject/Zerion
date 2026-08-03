package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.ZwfNonce;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_KP_ID_SIZE;
import static org.zerionproject.wire.ZwfConstants.FRAME_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.FRAME_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.MAC_LENGTH;
import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;
import static org.zerionproject.wire.ZwfConstants.SEGMENT_BODY;
import static org.zerionproject.wire.ZwfConstants.SEGMENT_FRAME_HEADER;
import static org.zerionproject.wire.ZwfConstants.SEGMENT_MODE3FULL_HEADER;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.wire.ZwfConstants.WIRE_VERSION;

/**
 * Send side of a Zerion 3.0 (ZWF) Mode 3-Full stream.
 *
 * <p>The Mode 3-Full frame format (see {@code PcsStreamEncrypterImpl}) rides the
 * native wire layer. The cryptography — per-message classical chain key,
 * per-frame ML-KEM re-encapsulation and hybrid body key — is unchanged. It
 * differs from that format in that:
 * <ul>
 * <li>the chain is seeded from a persistent, strictly-monotonic {@code streamId}
 * (never the resettable per-keyset counter);</li>
 * <li>the AEAD nonce binds {@code streamId} as well as the frame number
 * ({@link ZwfNonce});</li>
 * <li>every frame is padded to a fixed {@link
 * org.zerionproject.wire.ZwfConstants#FRAME_LENGTH} bytes;</li>
 * <li>the stream header is a native Zerion header ({@code [version][streamId]});
 * the chain key is not sent because the persisted ratchet state seeds the chain
 * from {@code (rootKey, streamId)}.</li>
 * </ul>
 * There is no classical / Mode 2 / legacy-Mode 3 fallback: Mode 3-Full is the
 * only mode.
 */
@NotThreadSafe
@NotNullByDefault
public class ZwfMode3FullStreamEncrypter {

	private final OutputStream out;
	private final AuthenticatedCipher cipher;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final long streamId;
	private final byte[] tag;
	private final byte[] streamHeaderNonce;
	private final SecretKey streamHeaderKey;
	@Nullable
	private final Consumer<PcsSessionState> stateCallback;
	@Nullable
	private final java.util.function.Supplier<Mode3FullState> m3fRefresher;
	@Nullable
	private final Consumer<Mode3FullState> m3fCallback;
	@Nullable
	private final java.util.concurrent.locks.Lock directionLock;
	private final PcsHeaderCodec headerCodec;
	private final byte[] frameNonce;
	private final byte[] frameBuf = new byte[FRAME_LENGTH];
	private final boolean originatorIsAlice;

	private PcsSessionState sendState;
	private SecretKey streamChainKey;
	private int streamMessageNumber;
	private long frameNumber;
	private boolean writeTag, writeStreamHeader;

	public ZwfMode3FullStreamEncrypter(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, Mode3FullRatchet mode3FullRatchet, long streamId,
			byte[] tag, byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(out, cipher, ratchet, mode3FullRatchet, streamId, tag,
				streamHeaderNonce, streamHeaderKey, initialState, stateCallback,
				null, null, null, true);
	}

	/**
	 * Full constructor with the shared Mode 3-Full state hooks used by a duplex
	 * connection: {@code m3fRefresher} reads the state the receive side may have
	 * advanced (learning the peer's ML-KEM key), {@code m3fCallback} writes back
	 * the state after each send, and {@code directionLock} serialises access
	 * across the two directions.
	 */
	public ZwfMode3FullStreamEncrypter(OutputStream out,
			AuthenticatedCipher cipher, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet, long streamId, byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable java.util.function.Supplier<Mode3FullState> m3fRefresher,
			@Nullable Consumer<Mode3FullState> m3fCallback,
			@Nullable java.util.concurrent.locks.Lock directionLock,
			boolean originatorIsAlice) {
		if (streamId < 1) throw new IllegalArgumentException("streamId < 1");
		if (tag.length != org.zerionproject.wire.ZwfConstants.TAG_LENGTH)
			throw new IllegalArgumentException("bad tag length");
		if (streamHeaderNonce.length != NONCE_LENGTH)
			throw new IllegalArgumentException("bad stream-header nonce length");
		if (!initialState.isMode3Full())
			throw new IllegalArgumentException("state is not Mode 3-Full");
		this.out = out;
		this.cipher = cipher;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.streamId = streamId;
		this.tag = tag;
		this.streamHeaderNonce = streamHeaderNonce;
		this.streamHeaderKey = streamHeaderKey;
		this.sendState = initialState;
		this.stateCallback = stateCallback;
		this.m3fRefresher = m3fRefresher;
		this.m3fCallback = m3fCallback;
		this.directionLock = directionLock;
		this.originatorIsAlice = originatorIsAlice;
		this.headerCodec = new PcsHeaderCodec();
		this.frameNonce = new byte[NONCE_LENGTH];
		this.frameNumber = 0;
		this.writeTag = true;
		this.writeStreamHeader = true;
		SecretKey rootKey = initialState.getRootKey();
		if (rootKey == null) rootKey = initialState.getChainKey();
		this.streamChainKey = ratchet.deriveStreamInitialChainKey(rootKey,
				streamId, streamHeaderNonce);
		this.streamMessageNumber = 0;
	}

	/** Largest application payload a single Mode 3-Full frame can carry. */
	public int getMaxPayloadLength() {
		return maxPayloadFor(headerCodec.getMode3FullHeaderSize());
	}

	/**
	 * The largest application payload a single frame can carry, computed without
	 * a live encrypter (the Mode 3-Full header size is fixed). Used to size ZMM
	 * records for fragmentation before the stream is opened.
	 */
	public static int maxMessageLength() {
		return maxPayloadFor(new PcsHeaderCodec().getMode3FullHeaderSize());
	}

	private static int maxPayloadFor(int pcsHeaderSize) {
		return FRAME_LENGTH - FRAME_HEADER_LENGTH
				- (pcsHeaderSize + MAC_LENGTH) - MAC_LENGTH;
	}

	public void writeFrame(byte[] payload, int payloadLength, boolean finalFrame)
			throws IOException {
		if (payloadLength < 0 || payloadLength > payload.length)
			throw new IllegalArgumentException("payloadLength out of range");
		if (frameNumber < 0) throw new IOException("frame counter exhausted");

		int pcsHeaderSize = headerCodec.getMode3FullHeaderSize();
		int maxPayload = maxPayloadFor(pcsHeaderSize);
		if (payloadLength > maxPayload)
			throw new IllegalArgumentException("payload exceeds frame capacity");
		// Pad the body so every frame is exactly FRAME_LENGTH on the wire.
		int paddingLength = maxPayload - payloadLength;

		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();

		int messageNumber;
		int prevChainLength = 0;
		PublicKey dhPublicKey;
		Mode3FullRatchet.PqSendResult mode3FullSend;
		SecretKey classicalMessageKey;
		SecretKey bodyMessageKey;
		try {
			DhRatchetResult dhResult = ratchet.performSendDhRatchet(sendState);
			sendState = dhResult.getNewState();
			dhPublicKey = dhResult.getDhPublicKey();
		} catch (GeneralSecurityException | PcsException e) {
			throw new IOException("DH ratchet failed", e);
		}

		PcsRatchet.KdfCkResult streamKdf = ratchet.kdfCk(streamChainKey);
		classicalMessageKey = streamKdf.getMessageKey();
		SecretKey nextStreamChainKey = streamKdf.getNewChainKey();
		messageNumber = streamMessageNumber;

		if (directionLock != null) directionLock.lock();
		try {
			Mode3FullState m3fState = sendState.getMode3FullState();
			if (m3fState == null)
				throw new IOException("Mode 3-Full state missing");
			if (m3fRefresher != null) {
				Mode3FullState fresh = m3fRefresher.get();
				if (fresh != null) {
					long mergedCounter = Math.max(fresh.getMessageCounter(),
							m3fState.getMessageCounter());
					m3fState = new Mode3FullState(fresh.getTheirActivePqPk(),
							fresh.getOurActiveKeyPair(), fresh.getRecentKeyPairs(),
							mergedCounter);
					sendState = sendState.withMode3FullState(m3fState);
				}
			}
			mode3FullSend = mode3FullRatchet.pqEncapsulateSend(m3fState);
			sendState = sendState.withMode3FullState(mode3FullSend.getNewState());
			if (m3fCallback != null) {
				m3fCallback.accept(mode3FullSend.getNewState());
			}
		} finally {
			if (directionLock != null) directionLock.unlock();
		}
		bodyMessageKey = classicalMessageKey;
		byte[] ss = mode3FullSend.getSharedSecret();
		if (ss != null) {
			bodyMessageKey = mode3FullRatchet.deriveHybridMessageKey(
					classicalMessageKey, ss);
			SecretKey mixed = mode3FullRatchet.mixPqSecretIntoChainKey(
					nextStreamChainKey, ss);
			nextStreamChainKey.clear();
			nextStreamChainKey = mixed;
			Arrays.fill(ss, (byte) 0);
		}

		streamChainKey = nextStreamChainKey;
		streamMessageNumber++;
		if (stateCallback != null) stateCallback.accept(sendState);

		byte[] bodyPlaintext = null;
		try {
			KpId kpIdUsed = mode3FullSend.getKpIdUsed();
			byte[] kpIdBytes = kpIdUsed != null ? kpIdUsed.getBytes()
					: new byte[MODE3_FULL_KP_ID_SIZE];
			byte[] m3fHeader = headerCodec.encodeMode3FullHeader(messageNumber,
					prevChainLength, dhPublicKey.getEncoded(),
					mode3FullSend.getPkAdvertise(),
					mode3FullSend.getCiphertext(), kpIdBytes);
			if (m3fHeader.length != pcsHeaderSize)
				throw new IOException("unexpected Mode 3-Full header size");

			bodyPlaintext = new byte[pcsHeaderSize + payloadLength
					+ paddingLength];
			System.arraycopy(m3fHeader, 0, bodyPlaintext, 0, pcsHeaderSize);
			System.arraycopy(payload, 0, bodyPlaintext, pcsHeaderSize,
					payloadLength);

			int totalPayloadLength = pcsHeaderSize + payloadLength;

			byte[] frame = frameBuf;

			byte[] frameHeaderPlaintext =
					new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
			encodeFrameHeader(frameHeaderPlaintext, finalFrame,
					totalPayloadLength, paddingLength);
			encryptSegment(SEGMENT_FRAME_HEADER, classicalMessageKey,
					frameHeaderPlaintext, 0, FRAME_HEADER_PLAINTEXT_LENGTH,
					frame, 0);

			encryptSegment(SEGMENT_MODE3FULL_HEADER, classicalMessageKey,
					bodyPlaintext, 0, pcsHeaderSize, frame, FRAME_HEADER_LENGTH);

			int bodyOffset = FRAME_HEADER_LENGTH + pcsHeaderSize + MAC_LENGTH;
			encryptSegment(SEGMENT_BODY, bodyMessageKey, bodyPlaintext,
					pcsHeaderSize, payloadLength + paddingLength, frame,
					bodyOffset);

			out.write(frame);
			frameNumber++;
		} finally {
			if (bodyPlaintext != null) Arrays.fill(bodyPlaintext, (byte) 0);
			if (bodyMessageKey != classicalMessageKey) bodyMessageKey.clear();
			classicalMessageKey.clear();
		}
	}

	private void encryptSegment(int segment, SecretKey key, byte[] input,
			int inputOff, int len, byte[] output, int outputOff)
			throws IOException {
		ZwfNonce.encode(frameNonce, streamId, frameNumber, segment,
				originatorIsAlice);
		try {
			cipher.init(true, key, frameNonce);
			int encrypted = cipher.process(input, inputOff, len, output,
					outputOff);
			if (encrypted != len + MAC_LENGTH)
				throw new IOException("cipher produced wrong length");
		} catch (GeneralSecurityException e) {
			throw new IOException("segment encryption failed", e);
		}
	}

	private static void encodeFrameHeader(byte[] dest, boolean finalFrame,
			int totalPayloadLength, int paddingLength) {
		ByteUtils.writeUint16(totalPayloadLength, dest, 0);
		ByteUtils.writeUint16(paddingLength, dest, ByteUtils.INT_16_BYTES);
		if (finalFrame) dest[0] |= 0x80;
	}

	private void writeTag() throws IOException {
		out.write(tag, 0, tag.length);
		writeTag = false;
	}

	private void writeStreamHeader() throws IOException {
		byte[] plaintext = new byte[STREAM_HEADER_PLAINTEXT_LENGTH];
		ByteUtils.writeUint16(WIRE_VERSION, plaintext, 0);
		ByteUtils.writeUint64(streamId, plaintext, ByteUtils.INT_16_BYTES);
		byte[] header = new byte[STREAM_HEADER_LENGTH];
		System.arraycopy(streamHeaderNonce, 0, header, 0, NONCE_LENGTH);
		try {
			cipher.init(true, streamHeaderKey, streamHeaderNonce);
			int encrypted = cipher.process(plaintext, 0,
					STREAM_HEADER_PLAINTEXT_LENGTH, header, NONCE_LENGTH);
			if (encrypted != STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH)
				throw new IOException("stream header encryption wrong length");
		} catch (GeneralSecurityException e) {
			throw new IOException("stream header encryption failed", e);
		}
		out.write(header);
		writeStreamHeader = false;
	}

	PcsSessionState getState() {
		return sendState;
	}
}
