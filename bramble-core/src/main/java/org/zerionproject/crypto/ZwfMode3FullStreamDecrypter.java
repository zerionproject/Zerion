package org.zerionproject.crypto;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.KpId;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec.Mode3FullHeader;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.ZwfNonce;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

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
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.zerionproject.wire.ZwfConstants.WIRE_VERSION;

/**
 * Receive side of a Zerion 3.0 (ZWF) Mode 3-Full stream — the inverse of
 * {@link ZwfMode3FullStreamEncrypter}. Reads the native tag and stream header,
 * seeds its chain from {@code (rootKey, streamId)}, then opens each fixed-size
 * frame's three AEAD segments (frame header + Mode 3-Full header under the
 * classical message key, body under the hybrid key). Any authentication or
 * format failure surfaces as a {@link FormatException}; the stream must then be
 * dropped.
 */
@NotThreadSafe
@NotNullByDefault
public class ZwfMode3FullStreamDecrypter {

	private final InputStream in;
	private final AuthenticatedCipher cipher;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	@Nullable
	private final KeyParser keyParser;
	private final byte[] expectedTag;
	private final long expectedStreamId;
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
	private final byte[] frame;
	private final boolean originatorIsAlice;

	private PcsSessionState recvState;
	@Nullable
	private SecretKey streamChainKey;
	private long streamId;
	private int streamMessageNumber;
	private long frameNumber;
	private boolean finalFrame;
	private boolean streamStartRead;

	public ZwfMode3FullStreamDecrypter(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, Mode3FullRatchet mode3FullRatchet,
			@Nullable KeyParser keyParser, byte[] expectedTag,
			long expectedStreamId, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(in, cipher, ratchet, mode3FullRatchet, keyParser, expectedTag,
				expectedStreamId, streamHeaderKey, initialState, stateCallback,
				null, null, null, true);
	}

	/**
	 * Full constructor with the shared Mode 3-Full state hooks used by a duplex
	 * connection: {@code m3fRefresher}/{@code m3fCallback} share the state with
	 * the send side (so the peer key this side learns reaches the send side),
	 * and {@code directionLock} serialises access.
	 */
	public ZwfMode3FullStreamDecrypter(InputStream in,
			AuthenticatedCipher cipher, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet, @Nullable KeyParser keyParser,
			byte[] expectedTag, long expectedStreamId, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable java.util.function.Supplier<Mode3FullState> m3fRefresher,
			@Nullable Consumer<Mode3FullState> m3fCallback,
			@Nullable java.util.concurrent.locks.Lock directionLock,
			boolean originatorIsAlice) {
		if (expectedTag.length != TAG_LENGTH)
			throw new IllegalArgumentException("bad tag length");
		if (!initialState.isMode3Full())
			throw new IllegalArgumentException("state is not Mode 3-Full");
		this.in = in;
		this.cipher = cipher;
		this.ratchet = ratchet;
		this.mode3FullRatchet = mode3FullRatchet;
		this.keyParser = keyParser;
		this.expectedTag = expectedTag;
		this.expectedStreamId = expectedStreamId;
		this.streamHeaderKey = streamHeaderKey;
		this.recvState = initialState;
		this.stateCallback = stateCallback;
		this.m3fRefresher = m3fRefresher;
		this.m3fCallback = m3fCallback;
		this.directionLock = directionLock;
		this.originatorIsAlice = originatorIsAlice;
		this.headerCodec = new PcsHeaderCodec();
		this.frameNonce = new byte[NONCE_LENGTH];
		this.frame = new byte[FRAME_LENGTH];
		this.streamChainKey = null;
		this.streamMessageNumber = 0;
		this.frameNumber = 0;
		this.finalFrame = false;
		this.streamStartRead = false;
	}

	/**
	 * Reads and decrypts the next frame into {@code payloadOut}.
	 *
	 * @return the application payload length, or -1 after the final frame.
	 * @throws FormatException on any authentication or format failure.
	 */
	public int readFrame(byte[] payloadOut) throws IOException {
		if (finalFrame) return -1;
		if (frameNumber < 0) throw new IOException("frame counter exhausted");
		if (!streamStartRead) readStreamStart();

		readFully(frame, 0, FRAME_LENGTH);

		int pcsHeaderSize = headerCodec.getMode3FullHeaderSize();
		SecretKey classicalMK = null;
		SecretKey bodyMK = null;
		byte[] m3fHeaderPlain = null;
		byte[] bodyPlain = null;
		try {
			PcsRatchet.KdfCkResult streamKdf = ratchet.kdfCk(streamChainKey);
			classicalMK = streamKdf.getMessageKey();
			SecretKey nextStreamChainKey = streamKdf.getNewChainKey();

			byte[] frameHeaderPlain = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
			decryptSegment(SEGMENT_FRAME_HEADER, classicalMK, 0,
					FRAME_HEADER_LENGTH, frameHeaderPlain, 0);
			finalFrame = (frameHeaderPlain[0] & 0x80) != 0;
			int totalPayloadLength =
					ByteUtils.readUint16(frameHeaderPlain, 0) & 0x7FFF;
			int paddingLength = ByteUtils.readUint16(frameHeaderPlain,
					ByteUtils.INT_16_BYTES);
			if (totalPayloadLength < pcsHeaderSize) throw new FormatException();
			int actualPayloadLength = totalPayloadLength - pcsHeaderSize;

			int bodyOffset = FRAME_HEADER_LENGTH + pcsHeaderSize + MAC_LENGTH;
			int expectedEnd = bodyOffset + actualPayloadLength + paddingLength
					+ MAC_LENGTH;
			if (expectedEnd != FRAME_LENGTH) throw new FormatException();

			m3fHeaderPlain = new byte[pcsHeaderSize];
			decryptSegment(SEGMENT_MODE3FULL_HEADER, classicalMK,
					FRAME_HEADER_LENGTH, pcsHeaderSize + MAC_LENGTH,
					m3fHeaderPlain, 0);
			Mode3FullHeader m3fHeader;
			try {
				m3fHeader = headerCodec.decodeMode3Full(m3fHeaderPlain);
			} catch (PcsException e) {
				throw new FormatException();
			}
			if (m3fHeader.getMessageNumber() != streamMessageNumber)
				throw new FormatException();

			KpId kpId = parseKpId(m3fHeader.getKpId());
			bodyMK = classicalMK;
			if (directionLock != null) directionLock.lock();
			try {
				Mode3FullState m3fState = recvState.getMode3FullState();
				if (m3fState != null && m3fRefresher != null) {
					Mode3FullState fresh = m3fRefresher.get();
					if (fresh != null) {
						long mergedCounter = Math.max(fresh.getMessageCounter(),
								m3fState.getMessageCounter());
						m3fState = new Mode3FullState(
								m3fState.getTheirActivePqPk(),
								fresh.getOurActiveKeyPair(),
								fresh.getRecentKeyPairs(), mergedCounter);
						recvState = recvState.withMode3FullState(m3fState);
					}
				}
				if (m3fState != null) {
					byte[] ss;
					try {
						PqRecvResult pq = mode3FullRatchet.pqDecapsulateRecv(
								m3fState, kpId, m3fHeader.getKemCiphertext(),
								m3fHeader.getPkAdvertise());
						ss = pq.getSharedSecret();
						recvState = recvState.withMode3FullState(pq.getNewState());
						if (m3fCallback != null) {
							m3fCallback.accept(pq.getNewState());
						}
					} catch (PcsException | RuntimeException e) {
						throw new FormatException();
					}
					if (ss != null) {
						bodyMK = mode3FullRatchet.deriveHybridMessageKey(
								classicalMK, ss);
						SecretKey mixed =
								mode3FullRatchet.mixPqSecretIntoChainKey(
										nextStreamChainKey, ss);
						nextStreamChainKey.clear();
						nextStreamChainKey = mixed;
						Arrays.fill(ss, (byte) 0);
					}
				}
			} finally {
				if (directionLock != null) directionLock.unlock();
			}

			bodyPlain = new byte[actualPayloadLength + paddingLength];
			decryptSegment(SEGMENT_BODY, bodyMK, bodyOffset,
					actualPayloadLength + paddingLength + MAC_LENGTH, bodyPlain,
					0);
			for (int i = 0; i < paddingLength; i++) {
				if (bodyPlain[actualPayloadLength + i] != 0)
					throw new FormatException();
			}

			// Does not affect this frame's key, which comes from the stream chain.
			applyReceiveDhRatchet(m3fHeader.getDhPublicKey());

			if (actualPayloadLength > payloadOut.length)
				throw new FormatException();
			System.arraycopy(bodyPlain, 0, payloadOut, 0, actualPayloadLength);

			streamChainKey = nextStreamChainKey;
			streamMessageNumber++;
			frameNumber++;
			if (stateCallback != null) stateCallback.accept(recvState);
			return actualPayloadLength;
		} finally {
			if (bodyPlain != null) Arrays.fill(bodyPlain, (byte) 0);
			if (m3fHeaderPlain != null) Arrays.fill(m3fHeaderPlain, (byte) 0);
			if (classicalMK != null) classicalMK.clear();
			if (bodyMK != null && bodyMK != classicalMK) bodyMK.clear();
			Arrays.fill(frame, (byte) 0);
			Arrays.fill(frameNonce, (byte) 0);
		}
	}

	private void applyReceiveDhRatchet(byte[] dhKeyBytes) throws FormatException {
		if (keyParser == null || !recvState.isMode2()) return;
		org.zerionproject.core.api.crypto.pcs.DhRatchetState dhs =
				recvState.getDhState();
		PublicKey persistedRemote = dhs != null
				? dhs.getDhRemotePublicKey() : null;
		boolean isNewDhKey = persistedRemote == null
				|| !Arrays.equals(dhKeyBytes, persistedRemote.getEncoded());
		if (!isNewDhKey) return;
		PublicKey theirNewKey;
		try {
			theirNewKey = keyParser.parsePublicKey(dhKeyBytes);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		try {
			DhRatchetResult dhResult =
					ratchet.performReceiveDhRatchet(recvState, theirNewKey);
			recvState = dhResult.getNewState();
		} catch (GeneralSecurityException | PcsException | RuntimeException e) {
			throw new FormatException();
		}
	}

	@Nullable
	private static KpId parseKpId(byte[] kpIdBytes) {
		for (byte b : kpIdBytes) {
			if (b != 0) return new KpId(kpIdBytes);
		}
		return null;
	}

	private void decryptSegment(int segment, SecretKey key, int inOff, int len,
			byte[] output, int outOff) throws IOException {
		ZwfNonce.encode(frameNonce, streamId, frameNumber, segment,
				originatorIsAlice);
		try {
			cipher.init(false, key, frameNonce);
			int decrypted = cipher.process(frame, inOff, len, output, outOff);
			if (decrypted != len - MAC_LENGTH)
				throw new FormatException();
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private void readStreamStart() throws IOException {
		byte[] tag = new byte[TAG_LENGTH];
		readFully(tag, 0, TAG_LENGTH);
		if (!constantTimeEquals(tag, expectedTag))
			throw new FormatException();

		byte[] header = new byte[STREAM_HEADER_LENGTH];
		readFully(header, 0, STREAM_HEADER_LENGTH);
		byte[] nonce = new byte[NONCE_LENGTH];
		System.arraycopy(header, 0, nonce, 0, NONCE_LENGTH);
		byte[] plaintext = new byte[STREAM_HEADER_PLAINTEXT_LENGTH];
		try {
			cipher.init(false, streamHeaderKey, nonce);
			int decrypted = cipher.process(header, NONCE_LENGTH,
					STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH, plaintext, 0);
			if (decrypted != STREAM_HEADER_PLAINTEXT_LENGTH)
				throw new FormatException();
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
		int version = ByteUtils.readUint16(plaintext, 0);
		if (version != WIRE_VERSION) throw new FormatException();
		streamId = ByteUtils.readUint64(plaintext, ByteUtils.INT_16_BYTES);
		if (streamId < 1) throw new FormatException();
		// The stream id seeding the chain and AEAD nonce MUST be the tag's replay-validated id, or a stale header id could reuse the (rootKey, streamId) nonce space.
		if (expectedStreamId > 0 && streamId != expectedStreamId)
			throw new FormatException();

		SecretKey rootKey = recvState.getRootKey();
		if (rootKey == null) rootKey = recvState.getChainKey();
		streamChainKey = ratchet.deriveStreamInitialChainKey(rootKey, streamId,
				nonce);
		streamStartRead = true;
	}

	private void readFully(byte[] buf, int off, int len) throws IOException {
		int read = 0;
		while (read < len) {
			int r = in.read(buf, off + read, len - read);
			if (r == -1) throw new EOFException();
			read += r;
		}
	}

	private static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int result = 0;
		for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
		return result == 0;
	}

	public long getStreamId() {
		return streamId;
	}

	PcsSessionState getState() {
		return recvState;
	}
}
