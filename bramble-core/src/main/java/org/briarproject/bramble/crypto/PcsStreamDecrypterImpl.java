package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamDecrypter;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullState;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqChunk;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.crypto.pcs.PcsHeaderCodec;
import org.briarproject.bramble.crypto.pcs.PcsHeaderCodec.Mode3FullHeader;
import org.briarproject.bramble.crypto.pcs.PcsHeaderCodec.PcsHeader;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_FRAME_OVERHEAD;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_FULL_STREAM_FLAG;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MIN_SIZE;
import static org.briarproject.bramble.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.FRAME_HEADER_PLAINTEXT_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.FRAME_NONCE_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAC_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.briarproject.bramble.api.transport.TransportConstants.STREAM_HEADER_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.STREAM_HEADER_PLAINTEXT_LENGTH;
import static org.briarproject.bramble.util.ByteUtils.INT_16_BYTES;
import static org.briarproject.bramble.util.ByteUtils.INT_64_BYTES;

@NotThreadSafe
@NotNullByDefault
class PcsStreamDecrypterImpl implements StreamDecrypter {

	private static final Logger LOG =
			Logger.getLogger(PcsStreamDecrypterImpl.class.getName());

	private final InputStream in;
	private final AuthenticatedCipher cipher;
	private final PcsRatchet ratchet;
	private final SkippedKeyStore skippedKeyStore;
	private final byte[] chainId;
	private final long streamNumber;
	private final SecretKey streamHeaderKey;
	private final byte[] frameNonce, frameHeader, frameCiphertext;
	private final byte[] pcsHeaderBuffer;
	@Nullable
	private final Consumer<PcsSessionState> stateCallback;
	@Nullable
	private final KeyParser keyParser;
	@Nullable
	private final PqRatchet pqRatchet;
	@Nullable
	private final Consumer<PqRatchetState> pqStateCallback;
	@Nullable
	private final Consumer<SecretKey> pqCrossMixCallback;
	@Nullable
	private final Mode3FullRatchet mode3FullRatchet;
	@Nullable
	private final java.util.function.Supplier<
			org.briarproject.bramble.api.crypto.pcs.Mode3FullState>
			mode3FullStateRefresher;
	private final PcsHeaderCodec headerCodec;

	@Nullable
	private PcsSessionState recvState;
	@Nullable
	private PqRatchetState pqState;
	private long frameNumber;
	private boolean finalFrame;
	private boolean pcsEnabled;
	private boolean mode2Enabled;
	private boolean mode3Enabled;
	private boolean mode3FullEnabled;
	private boolean streamHeaderRead;
	@Nullable
	private PublicKey lastReceivedDhKey;

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, null,
				null, null, null, null, null, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				null, null, null, null, null, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				pqRatchet, initialPqState, pqStateCallback, null, null, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback,
			@Nullable Consumer<SecretKey> pqCrossMixCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				pqRatchet, initialPqState, pqStateCallback, pqCrossMixCallback,
				null, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback,
			@Nullable Consumer<SecretKey> pqCrossMixCallback,
			@Nullable Mode3FullRatchet mode3FullRatchet) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				pqRatchet, initialPqState, pqStateCallback, pqCrossMixCallback,
				mode3FullRatchet, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback,
			@Nullable Consumer<SecretKey> pqCrossMixCallback,
			@Nullable Mode3FullRatchet mode3FullRatchet,
			@Nullable java.util.function.Supplier<
					org.briarproject.bramble.api.crypto.pcs.Mode3FullState>
					mode3FullStateRefresher) {
		this.in = in;
		this.cipher = cipher;
		this.ratchet = ratchet;
		this.skippedKeyStore = skippedKeyStore;
		this.chainId = chainId;
		this.streamNumber = streamNumber;
		this.streamHeaderKey = streamHeaderKey;
		this.recvState = initialState;
		this.stateCallback = stateCallback;
		this.keyParser = keyParser;
		this.pqRatchet = pqRatchet;
		this.pqState = initialPqState;
		this.pqStateCallback = pqStateCallback;
		this.pqCrossMixCallback = pqCrossMixCallback;
		this.mode3FullRatchet = mode3FullRatchet;
		this.mode3FullStateRefresher = mode3FullStateRefresher;
		this.headerCodec = new PcsHeaderCodec();
		int mode3FullHeaderSize = PCS_MODE3_HEADER_MIN_SIZE +
				MODE3_FULL_FRAME_OVERHEAD;
		int maxHeader = Math.max(PCS_MODE3_HEADER_MAX_SIZE, mode3FullHeaderSize);
		frameNonce = new byte[FRAME_NONCE_LENGTH];
		frameHeader = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
		frameCiphertext = new byte[MAX_FRAME_LENGTH + maxHeader + MAC_LENGTH];
		pcsHeaderBuffer = new byte[maxHeader];
		frameNumber = 0;
		finalFrame = false;
		pcsEnabled = false;
		mode2Enabled = false;
		mode3Enabled = false;
		mode3FullEnabled = false;
		streamHeaderRead = false;
		lastReceivedDhKey = null;
	}

	@Override
	public int readFrame(byte[] payload) throws IOException {
		LOG.warning("[ZER-PQ-DEBUG] readFrame entry: streamHeaderRead=" +
				streamHeaderRead + " recvStateNull=" + (recvState == null) +
				" frameNumber=" + frameNumber);
		if (payload.length < MAX_PAYLOAD_LENGTH)
			throw new IllegalArgumentException();
		if (finalFrame) return -1;
		if (frameNumber < 0) throw new IOException();
		if (!streamHeaderRead) {
			if (recvState == null) {
				readStreamHeader();
			} else {
				skipStreamHeader();
			}
		}
		if (recvState == null) throw new IllegalStateException();

		SecretKey messageKey;
		int messageNumber;

		int offset = 0;
		while (offset < FRAME_HEADER_LENGTH) {
			int read = in.read(frameCiphertext, offset, FRAME_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}

		int expectedMsgNum = recvState.getMessageNumber();
		boolean useMode3Full = MODE3_FULL_ENABLED && mode3FullEnabled
				&& recvState.isMode3Full() && mode3FullRatchet != null;
		try {
			AdvanceResult advanceResult = ratchet.advanceReceiveChain(
					recvState, expectedMsgNum, skippedKeyStore);
			SecretKey classicalMK = advanceResult.getMessageKey();
			if (useMode3Full) {
				Mode3FullState m3fState = recvState.getMode3FullState();
				if (m3fState == null) throw new FormatException();
				messageKey = mode3FullRatchet.deriveHybridMessageKey(
						classicalMK, m3fState.getCkPq());
			} else {
				messageKey = classicalMK;
			}
			messageNumber = expectedMsgNum;

			FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
			cipher.init(false, messageKey, frameNonce);
			int decrypted = cipher.process(frameCiphertext, 0,
					FRAME_HEADER_LENGTH, frameHeader, 0);
			if (decrypted != FRAME_HEADER_PLAINTEXT_LENGTH)
				throw new RuntimeException();

			recvState = advanceResult.getNewState();
		} catch (GeneralSecurityException | PcsException e) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: frame-header decrypt — useMode3Full=" +
					useMode3Full + " err=" + e.getClass().getSimpleName() +
					" msg=" + e.getMessage());
			throw new FormatException();
		}

		finalFrame = FrameEncoder.isFinalFrame(frameHeader);
		int totalPayloadLength = FrameEncoder.getPayloadLength(frameHeader);
		int paddingLength = FrameEncoder.getPaddingLength(frameHeader);

		if (totalPayloadLength < PCS_HEADER_MAX_SIZE) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: totalPayloadLength " +
					totalPayloadLength + " < PCS_HEADER_MAX_SIZE " +
					PCS_HEADER_MAX_SIZE);
			throw new FormatException();
		}
		if (totalPayloadLength + paddingLength > MAX_PAYLOAD_LENGTH + PCS_MODE3_HEADER_MAX_SIZE) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: total " +
					(totalPayloadLength + paddingLength) +
					" > limit " + (MAX_PAYLOAD_LENGTH + PCS_MODE3_HEADER_MAX_SIZE));
			throw new FormatException();
		}

		int frameLength = FRAME_HEADER_LENGTH + totalPayloadLength + paddingLength + MAC_LENGTH;
		while (offset < frameLength) {
			int read = in.read(frameCiphertext, offset, frameLength - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}

		byte[] decryptedPayload = new byte[totalPayloadLength + paddingLength];
		FrameEncoder.encodeNonce(frameNonce, frameNumber, false);
		try {
			cipher.init(false, messageKey, frameNonce);
			int decrypted = cipher.process(frameCiphertext, FRAME_HEADER_LENGTH,
					totalPayloadLength + paddingLength + MAC_LENGTH,
					decryptedPayload, 0);
			if (decrypted != totalPayloadLength + paddingLength)
				throw new RuntimeException();
		} catch (GeneralSecurityException e) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: payload decrypt — " +
					e.getClass().getSimpleName() + " useMode3Full=" + useMode3Full);
			throw new FormatException();
		}

		PcsHeader pcsHeader = null;
		Mode3FullHeader m3fHeader = null;
		int pcsHeaderSize;
		byte[] dhKeyBytes = null;
		boolean hasDhRatchet = false;
		try {
			if (useMode3Full) {
				LOG.warning("[ZER-PQ-DEBUG] decoding Mode3Full header" +
						" (payloadBytes=" + decryptedPayload.length + ")");
				m3fHeader = headerCodec.decodeMode3Full(decryptedPayload);
				if (m3fHeader.getMessageNumber() != messageNumber) {
					LOG.warning("[ZER-PQ-DEBUG] FAIL: m3f msgNum " +
							m3fHeader.getMessageNumber() + " != expected " +
							messageNumber);
					throw new FormatException();
				}
				pcsHeaderSize = headerCodec.getMode3FullHeaderSize();
				dhKeyBytes = m3fHeader.getDhPublicKey();
				hasDhRatchet = true;
			} else {
				pcsHeader = headerCodec.decode(decryptedPayload);
				if (!pcsHeader.isPcsEnabled()) throw new FormatException();
				if (pcsHeader.getMessageNumber() != messageNumber)
					throw new FormatException();

				if (pcsHeader.isPqEnabled()) {
					pcsHeaderSize = headerCodec.getMode3HeaderSize(
							pcsHeader.getPqChunk());
				} else {
					pcsHeaderSize = PCS_HEADER_MAX_SIZE;
				}
				if (pcsHeader.hasDhRatchet()) {
					dhKeyBytes = pcsHeader.getDhPublicKey();
					hasDhRatchet = true;
				}
			}
		} catch (PcsException e) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: header decode PcsException — " +
					e.getMessage());
			throw new FormatException();
		}

		if (totalPayloadLength < pcsHeaderSize) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: totalPayload " +
					totalPayloadLength + " < pcsHeaderSize " + pcsHeaderSize);
			throw new FormatException();
		}

		if (hasDhRatchet && dhKeyBytes != null) {
			boolean isNewDhKey = true;
			if (lastReceivedDhKey != null) {
				byte[] lastKeyBytes = lastReceivedDhKey.getEncoded();
				isNewDhKey = !Arrays.equals(dhKeyBytes, lastKeyBytes);
			}

			if (isNewDhKey && recvState != null && recvState.isMode2()) {
				PublicKey theirNewKey = parseDhPublicKey(dhKeyBytes);
				if (theirNewKey != null) {
					try {
						DhRatchetResult dhResult = ratchet.performReceiveDhRatchet(
								recvState, theirNewKey);
						recvState = dhResult.getNewState();
						lastReceivedDhKey = theirNewKey;
					} catch (GeneralSecurityException | PcsException e) {
						throw new FormatException();
					}
				}
			}
		}
		if (useMode3Full && m3fHeader != null && recvState != null
				&& mode3FullRatchet != null) {
			Mode3FullState m3fState = recvState.getMode3FullState();
			if (m3fState != null && mode3FullStateRefresher != null) {
				Mode3FullState fresh = mode3FullStateRefresher.get();
				if (fresh != null) {
					m3fState = new Mode3FullState(
							m3fState.getCkPq(),
							m3fState.getTheirActivePqPk(),
							fresh.getOurActiveKeyPair(),
							fresh.getRecentKeyPairs(),
							m3fState.getMessageCounter());
					recvState = recvState.withMode3FullState(m3fState);
				}
			}
			if (m3fState != null) {
				try {
					byte[] kpIdBytes = m3fHeader.getKpId();
					org.briarproject.bramble.api.crypto.pcs.KpId kpId = null;
					for (byte b : kpIdBytes) {
						if (b != 0) {
							kpId = new org.briarproject.bramble.api.crypto.pcs.KpId(kpIdBytes);
							break;
						}
					}
					PqRecvResult pqResult = mode3FullRatchet.pqDecapsulateRecv(
							m3fState, kpId, m3fHeader.getKemCiphertext(),
							m3fHeader.getPkAdvertise());
					recvState = recvState.withMode3FullState(
							pqResult.getNewState());
				} catch (PcsException e) {
					LOG.warning("[ZER-PQ-DEBUG] FAIL: pqDecapsulateRecv — "
							+ e.getMessage());
					throw new FormatException();
				}
			}
		}
		if (mode3Enabled && !useMode3Full && pcsHeader != null
				&& !pcsHeader.isPqEnabled()) {
			throw new FormatException();
		}

		if (pcsHeader != null && pcsHeader.isPqEnabled() && pqRatchet != null
				&& pqState != null) {
			PqChunk chunk = pcsHeader.getPqChunk();
			if (chunk != null) {
				pqState = pqRatchet.processChunkReceived(pqState, chunk);
			}
			if (pqRatchet.isEpochComplete(pqState) &&
					recvState != null && recvState.getRootKey() != null) {
				try {
					SecretKey pqSecret = pqRatchet.deriveEpochSecret(pqState);
					SecretKey newRootKey = pqRatchet.mixPqSecretIntoRootKey(
							recvState.getRootKey(), pqSecret);
					recvState = recvState.afterPqRatchet(newRootKey,
							pqState.getCurrentEpoch());
					if (pqCrossMixCallback != null) {
						pqCrossMixCallback.accept(pqSecret);
					}
					pqState = pqRatchet.completeEpoch(pqState,
							System.currentTimeMillis());
				} catch (Exception e) {
					pqState = pqRatchet.initialize(System.currentTimeMillis());
				}
			}
			if (pqStateCallback != null) {
				pqStateCallback.accept(pqState);
			}
		}

		int actualPayloadLength = totalPayloadLength - pcsHeaderSize;
		System.arraycopy(decryptedPayload, pcsHeaderSize, payload, 0, actualPayloadLength);

		for (int i = 0; i < paddingLength; i++) {
			if (decryptedPayload[totalPayloadLength + i] != 0)
				throw new FormatException();
		}

		java.util.Arrays.fill(decryptedPayload, (byte) 0);

		frameNumber++;

		if (stateCallback != null) {
			stateCallback.accept(recvState);
		}

		return actualPayloadLength;
	}

	@Nullable
	private PublicKey parseDhPublicKey(byte[] keyBytes) throws FormatException {
		if (keyParser == null) {
			return null;
		}
		try {
			return keyParser.parsePublicKey(keyBytes);
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}
	}

	private void readStreamHeader() throws IOException {
		byte[] streamHeaderCiphertext = new byte[STREAM_HEADER_LENGTH];
		byte[] streamHeaderPlaintext = new byte[STREAM_HEADER_PLAINTEXT_LENGTH];
		int offset = 0;
		while (offset < STREAM_HEADER_LENGTH) {
			int read = in.read(streamHeaderCiphertext, offset,
					STREAM_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
		byte[] streamHeaderNonce = new byte[STREAM_HEADER_NONCE_LENGTH];
		System.arraycopy(streamHeaderCiphertext, 0, streamHeaderNonce, 0,
				STREAM_HEADER_NONCE_LENGTH);
		try {
			cipher.init(false, streamHeaderKey, streamHeaderNonce);
			int decrypted = cipher.process(streamHeaderCiphertext,
					STREAM_HEADER_NONCE_LENGTH,
					STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH,
					streamHeaderPlaintext, 0);
			if (decrypted != STREAM_HEADER_PLAINTEXT_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException e) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: stream-header decrypt — " +
					e.getClass().getSimpleName());
			throw new FormatException();
		}
		int receivedProtocolVersion = ByteUtils.readUint16(streamHeaderPlaintext, 0);
		pcsEnabled = (receivedProtocolVersion & 0x8000) != 0;
		mode2Enabled = (receivedProtocolVersion & 0x4000) != 0;
		mode3Enabled = (receivedProtocolVersion & 0x2000) != 0;
		mode3FullEnabled = MODE3_FULL_ENABLED &&
				(receivedProtocolVersion & MODE3_FULL_STREAM_FLAG) != 0;
		int baseVersion = receivedProtocolVersion & 0x0FFF;
		LOG.warning("[ZER-PQ-DEBUG] stream-header parsed: version=0x" +
				Integer.toHexString(receivedProtocolVersion) +
				" pcs=" + pcsEnabled + " m2=" + mode2Enabled +
				" m3=" + mode3Enabled + " m3Full=" + mode3FullEnabled +
				" base=" + baseVersion);
		if (baseVersion != PROTOCOL_VERSION) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: baseVersion " + baseVersion +
					" != PROTOCOL_VERSION " + PROTOCOL_VERSION);
			throw new FormatException();
		}
		if (!pcsEnabled || !mode2Enabled) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: pcsEnabled=" + pcsEnabled +
					" mode2Enabled=" + mode2Enabled);
			throw new FormatException();
		}
		long receivedStreamNumber = ByteUtils.readUint64(streamHeaderPlaintext, INT_16_BYTES);
		if (receivedStreamNumber != streamNumber) {
			LOG.warning("[ZER-PQ-DEBUG] FAIL: streamNumber " +
					receivedStreamNumber + " != expected " + streamNumber);
			throw new FormatException();
		}
		byte[] chainKeyBytes = new byte[SecretKey.LENGTH];
		System.arraycopy(streamHeaderPlaintext, INT_16_BYTES + INT_64_BYTES,
				chainKeyBytes, 0, SecretKey.LENGTH);
		SecretKey chainKey = new SecretKey(chainKeyBytes);
		java.util.Arrays.fill(streamHeaderPlaintext, (byte) 0);
		recvState = ratchet.initializeMode2AsInitiator(chainKey);
		streamHeaderRead = true;
	}

	private void skipStreamHeader() throws IOException {
		byte[] streamHeaderCiphertext = new byte[STREAM_HEADER_LENGTH];
		int offset = 0;
		while (offset < STREAM_HEADER_LENGTH) {
			int read = in.read(streamHeaderCiphertext, offset,
					STREAM_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}
		pcsEnabled = true;
		mode2Enabled = recvState != null && recvState.isMode2();
		mode3Enabled = recvState != null && recvState.isMode3();
		mode3FullEnabled = MODE3_FULL_ENABLED && recvState != null
				&& recvState.isMode3Full();
		streamHeaderRead = true;
	}

	@Nullable
	public PcsSessionState getState() {
		return recvState;
	}

	@Nullable
	public PqRatchetState getPqState() {
		return pqState;
	}

	public boolean isPcsEnabled() {
		return pcsEnabled;
	}

	public boolean isMode2() {
		return mode2Enabled;
	}

	public boolean isMode3() {
		return mode3Enabled;
	}
}
