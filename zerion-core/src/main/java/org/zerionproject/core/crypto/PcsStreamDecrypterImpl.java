package org.zerionproject.core.crypto;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqChunk;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec.Mode3FullHeader;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec.PcsHeader;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import org.zerionproject.core.crypto.pcs.PcsPersistenceException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_ENABLED;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_FRAME_OVERHEAD;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_STREAM_FLAG;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MIN_SIZE;
import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.FRAME_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.FRAME_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAC_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_FRAME_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_PAYLOAD_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_PLAINTEXT_LENGTH;
import static org.zerionproject.core.util.ByteUtils.INT_16_BYTES;
import static org.zerionproject.core.util.ByteUtils.INT_64_BYTES;

@NotThreadSafe
@NotNullByDefault
class PcsStreamDecrypterImpl implements StreamDecrypter {

	private final InputStream in;
	private final AuthenticatedCipher cipher;
	private final PcsRatchet ratchet;
	private final SkippedKeyStore skippedKeyStore;
	private final byte[] chainId;
	private final long streamNumber;
	private final SecretKey streamHeaderKey;
	private final byte[] frameNonce, frameHeader, frameCiphertext;
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
	private final java.util.function.Supplier<Mode3FullState>
			mode3FullStateRefresher;
	@Nullable
	private final java.util.function.Supplier<PcsSessionState>
			sessionStateRefresher;
	@Nullable
	private final java.util.concurrent.locks.Lock directionLock;
	private final PcsHeaderCodec headerCodec;

	@Nullable
	private PcsSessionState recvState;
	@Nullable
	private PqRatchetState pqState;
	private long frameNumber;
	@Nullable
	private SecretKey streamChainKey;
	private int streamMessageNumber;
	private boolean finalFrame;
	private boolean pcsEnabled;
	private boolean mode2Enabled;
	private boolean mode3Enabled;
	private boolean mode3FullEnabled;
	private boolean streamHeaderRead;

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, null,
				null, null, null, null, null, null, null, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				null, null, null, null, null, null, null, null);
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
				pqRatchet, initialPqState, pqStateCallback, null, null, null,
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
			@Nullable Consumer<SecretKey> pqCrossMixCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				pqRatchet, initialPqState, pqStateCallback, pqCrossMixCallback,
				null, null, null, null);
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
			@Nullable java.util.function.Supplier<Mode3FullState>
					mode3FullStateRefresher) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, keyParser,
				pqRatchet, initialPqState, pqStateCallback, pqCrossMixCallback,
				mode3FullRatchet, mode3FullStateRefresher, null, null);
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
			@Nullable java.util.function.Supplier<Mode3FullState>
					mode3FullStateRefresher,
			@Nullable java.util.function.Supplier<PcsSessionState>
					sessionStateRefresher,
			@Nullable java.util.concurrent.locks.Lock directionLock) {
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
		this.sessionStateRefresher = sessionStateRefresher;
		this.directionLock = directionLock;
		this.headerCodec = new PcsHeaderCodec();
		int mode3FullHeaderSize = PCS_MODE3_HEADER_MIN_SIZE +
				MODE3_FULL_FRAME_OVERHEAD;
		int maxHeader = Math.max(PCS_MODE3_HEADER_MAX_SIZE, mode3FullHeaderSize);
		frameNonce = new byte[FRAME_NONCE_LENGTH];
		frameHeader = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
		frameCiphertext = new byte[MAX_FRAME_LENGTH + maxHeader + MAC_LENGTH];
		frameNumber = 0;
		finalFrame = false;
		pcsEnabled = false;
		mode2Enabled = false;
		mode3Enabled = false;
		mode3FullEnabled = false;
		streamHeaderRead = false;
		if (initialState != null) {
			SecretKey rootKey = initialState.getRootKey();
			if (rootKey == null) rootKey = initialState.getChainKey();
			this.streamChainKey = ratchet.deriveStreamInitialChainKey(rootKey,
					streamNumber);
		} else {
			this.streamChainKey = null;
		}
		this.streamMessageNumber = 0;
	}

	@Override
	public int readFrame(byte[] payload) throws IOException {
		if (payload.length < MAX_PAYLOAD_LENGTH)
			throw new IllegalArgumentException();
		if (finalFrame) return -1;
		if (frameNumber < 0) throw new IOException();
		return readFrameLocked(payload);
	}

	private int readFrameLocked(byte[] payload) throws IOException {
		if (sessionStateRefresher != null) {
			PcsSessionState fresh = sessionStateRefresher.get();
			if (fresh != null) {
				recvState = fresh;
			}
		}
		if (!streamHeaderRead) {
			if (recvState == null) {
				readStreamHeader();
			} else {
				skipStreamHeader();
			}
		}
		if (recvState == null) throw new IllegalStateException();

		SecretKey messageKey = null;
		int messageNumber;

		int offset = 0;
		while (offset < FRAME_HEADER_LENGTH) {
			int read = in.read(frameCiphertext, offset, FRAME_HEADER_LENGTH - offset);
			if (read == -1) throw new EOFException();
			offset += read;
		}

		boolean useMode3Full = MODE3_FULL_ENABLED && mode3FullEnabled
				&& recvState.isMode3Full() && mode3FullRatchet != null;
		SecretKey classicalMK = null;
		SecretKey nextStreamChainKey;
		try {
			if (streamChainKey == null) {
				SecretKey rootKey = recvState.getRootKey();
				if (rootKey == null) rootKey = recvState.getChainKey();
				streamChainKey = ratchet.deriveStreamInitialChainKey(rootKey,
						streamNumber);
			}
			PcsRatchet.KdfCkResult streamKdf = ratchet.kdfCk(streamChainKey);
			classicalMK = streamKdf.getMessageKey();
			nextStreamChainKey = streamKdf.getNewChainKey();
			messageNumber = streamMessageNumber;

			if (useMode3Full) {
				FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 0);
			} else {
				FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
			}
			cipher.init(false, classicalMK, frameNonce);
			int decrypted = cipher.process(frameCiphertext, 0,
					FRAME_HEADER_LENGTH, frameHeader, 0);
			if (decrypted != FRAME_HEADER_PLAINTEXT_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException e) {
			throw new FormatException();
		}

		finalFrame = FrameEncoder.isFinalFrame(frameHeader);
		int totalPayloadLength = FrameEncoder.getPayloadLength(frameHeader);
		int paddingLength = FrameEncoder.getPaddingLength(frameHeader);

		if (totalPayloadLength < PCS_HEADER_MAX_SIZE) {
			throw new FormatException();
		}
		int maxAllowedTotal = useMode3Full
				? MAX_PAYLOAD_LENGTH + PCS_MODE3_HEADER_MIN_SIZE +
						org.zerionproject.core.api.crypto.pcs.PcsConstants
								.MODE3_FULL_FRAME_OVERHEAD
				: MAX_PAYLOAD_LENGTH + PCS_MODE3_HEADER_MAX_SIZE;
		if (totalPayloadLength + paddingLength > maxAllowedTotal) {
			throw new FormatException();
		}

		byte[] decryptedPayload = null;
		boolean stateLocked = false;
		try {
		messageKey = classicalMK;
		if (useMode3Full) {
			int pcsHdrSz = headerCodec.getMode3FullHeaderSize();
			if (totalPayloadLength < pcsHdrSz) {
				throw new FormatException();
			}
			int actualPayloadLength = totalPayloadLength - pcsHdrSz;
			int needBytes = FRAME_HEADER_LENGTH + pcsHdrSz + MAC_LENGTH
					+ actualPayloadLength + paddingLength + MAC_LENGTH;
			if (needBytes > frameCiphertext.length) throw new FormatException();
			while (offset < needBytes) {
				int read = in.read(frameCiphertext, offset,
						needBytes - offset);
				if (read == -1) throw new EOFException();
				offset += read;
			}

			if (directionLock != null) {
				directionLock.lock();
				stateLocked = true;
			}

			decryptedPayload = new byte[totalPayloadLength + paddingLength];
			FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 1);
			try {
				cipher.init(false, classicalMK, frameNonce);
				int decrypted = cipher.process(frameCiphertext,
						FRAME_HEADER_LENGTH, pcsHdrSz + MAC_LENGTH,
						decryptedPayload, 0);
				if (decrypted != pcsHdrSz)
					throw new RuntimeException();
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}

			Mode3FullHeader m3fHeaderEarly;
			try {
				m3fHeaderEarly = headerCodec.decodeMode3Full(decryptedPayload);
			} catch (PcsException e) {
				throw new FormatException();
			}
			byte[] kpIdBytes = m3fHeaderEarly.getKpId();
			org.zerionproject.core.api.crypto.pcs.KpId kpId = null;
			for (byte b : kpIdBytes) {
				if (b != 0) {
					kpId = new org.zerionproject.core.api.crypto.pcs.KpId(
							kpIdBytes);
					break;
				}
			}
			byte[] sharedSecret = null;
			Mode3FullState m3fState = recvState.getMode3FullState();
			if (m3fState != null && mode3FullStateRefresher != null) {
				Mode3FullState fresh = mode3FullStateRefresher.get();
				if (fresh != null) {
					long mergedCounter = Math.max(
							fresh.getMessageCounter(),
							m3fState.getMessageCounter());
					m3fState = new Mode3FullState(
							m3fState.getTheirActivePqPk(),
							fresh.getOurActiveKeyPair(),
							fresh.getRecentKeyPairs(),
							mergedCounter);
					recvState = recvState.withMode3FullState(m3fState);
				}
			}
			if (m3fState != null && mode3FullRatchet != null) {
				try {
					PqRecvResult pqResult =
							mode3FullRatchet.pqDecapsulateRecv(m3fState, kpId,
									m3fHeaderEarly.getKemCiphertext(),
									m3fHeaderEarly.getPkAdvertise());
					sharedSecret = pqResult.getSharedSecret();
					recvState = recvState.withMode3FullState(
							pqResult.getNewState());
				} catch (PcsException | RuntimeException e) {
					relearnPeerKeyForRecovery(m3fState,
							m3fHeaderEarly.getPkAdvertise());
					throw new FormatException();
				}
			}
			if (sharedSecret != null) {
				messageKey = mode3FullRatchet.deriveHybridMessageKey(
						classicalMK, sharedSecret);
				java.util.Arrays.fill(sharedSecret, (byte) 0);
			}

			FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 2);
			int bodyOffset = FRAME_HEADER_LENGTH + pcsHdrSz + MAC_LENGTH;
			try {
				cipher.init(false, messageKey, frameNonce);
				int decrypted = cipher.process(frameCiphertext, bodyOffset,
						actualPayloadLength + paddingLength + MAC_LENGTH,
						decryptedPayload, pcsHdrSz);
				if (decrypted != actualPayloadLength + paddingLength)
					throw new RuntimeException();
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}
		} else {
			int frameLength = FRAME_HEADER_LENGTH + totalPayloadLength
					+ paddingLength + MAC_LENGTH;
			if (frameLength > frameCiphertext.length) throw new FormatException();
			while (offset < frameLength) {
				int read = in.read(frameCiphertext, offset,
						frameLength - offset);
				if (read == -1) throw new EOFException();
				offset += read;
			}

			if (directionLock != null) {
				directionLock.lock();
				stateLocked = true;
			}

			decryptedPayload = new byte[totalPayloadLength + paddingLength];
			FrameEncoder.encodeNonce(frameNonce, frameNumber, false);
			try {
				cipher.init(false, classicalMK, frameNonce);
				int decrypted = cipher.process(frameCiphertext,
						FRAME_HEADER_LENGTH,
						totalPayloadLength + paddingLength + MAC_LENGTH,
						decryptedPayload, 0);
				if (decrypted != totalPayloadLength + paddingLength)
					throw new RuntimeException();
			} catch (GeneralSecurityException e) {
				throw new FormatException();
			}
		}

		PcsHeader pcsHeader = null;
		Mode3FullHeader m3fHeader = null;
		int pcsHeaderSize;
		byte[] dhKeyBytes = null;
		boolean hasDhRatchet = false;
		try {
			if (useMode3Full) {
				m3fHeader = headerCodec.decodeMode3Full(decryptedPayload);
				if (m3fHeader.getMessageNumber() != messageNumber) {
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
			throw new FormatException();
		}

		if (totalPayloadLength < pcsHeaderSize) {
			throw new FormatException();
		}

		if (hasDhRatchet && dhKeyBytes != null && recvState != null
				&& recvState.isMode2()) {
			if (sessionStateRefresher != null) {
				PcsSessionState freshSession = sessionStateRefresher.get();
				if (freshSession != null) {
					recvState = freshSession.withMode3FullState(
							recvState.getMode3FullState());
				}
			}
			org.zerionproject.core.api.crypto.pcs.DhRatchetState dhs =
					recvState.getDhState();
			PublicKey persistedRemote = dhs != null
					? dhs.getDhRemotePublicKey() : null;
			boolean isNewDhKey = persistedRemote == null
					|| !Arrays.equals(dhKeyBytes, persistedRemote.getEncoded());
			if (isNewDhKey) {
				PublicKey theirNewKey = parseDhPublicKey(dhKeyBytes);
				if (theirNewKey != null) {
					try {
						DhRatchetResult dhResult = ratchet.performReceiveDhRatchet(
								recvState, theirNewKey);
						recvState = dhResult.getNewState();
					} catch (GeneralSecurityException | PcsException
							| RuntimeException e) {
						throw new FormatException();
					}
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
						try {
							pqCrossMixCallback.accept(pqSecret);
						} catch (PcsPersistenceException __pe) {
							throw new IOException("Ratchet state persistence failed", __pe);
						}
					}
					pqState = pqRatchet.completeEpoch(pqState,
							System.currentTimeMillis());
				} catch (PcsException e) {
					throw new IOException("PQ epoch completion failed", e);
				}
			}
			if (pqStateCallback != null) {
				try {
					pqStateCallback.accept(pqState);
				} catch (PcsPersistenceException __pe) {
					throw new IOException("Ratchet state persistence failed", __pe);
				}
			}
		}

		int actualPayloadLength = totalPayloadLength - pcsHeaderSize;
		if (actualPayloadLength < 0 || actualPayloadLength > payload.length) {
			throw new FormatException();
		}
		System.arraycopy(decryptedPayload, pcsHeaderSize, payload, 0, actualPayloadLength);

		for (int i = 0; i < paddingLength; i++) {
			if (decryptedPayload[totalPayloadLength + i] != 0)
				throw new FormatException();
		}

		streamChainKey = nextStreamChainKey;
		streamMessageNumber++;
		frameNumber++;

		if (stateCallback != null) {
			try {
				stateCallback.accept(recvState);
			} catch (PcsPersistenceException __pe) {
				throw new IOException("Ratchet state persistence failed", __pe);
			}
		}

		return actualPayloadLength;
		} finally {
			if (stateLocked && directionLock != null) {
				directionLock.unlock();
			}
			if (decryptedPayload != null) {
				java.util.Arrays.fill(decryptedPayload, (byte) 0);
			}
			if (classicalMK != null) classicalMK.clear();
			if (messageKey != null && messageKey != classicalMK) {
				messageKey.clear();
			}
			java.util.Arrays.fill(frameCiphertext, (byte) 0);
			java.util.Arrays.fill(frameHeader, (byte) 0);
			java.util.Arrays.fill(frameNonce, (byte) 0);
		}
	}

	private void relearnPeerKeyForRecovery(Mode3FullState m3fState,
			byte[] theirAdvertisedPk) {
		try {
			Mode3FullState advanced = m3fState.withRecvAdvance(
					theirAdvertisedPk);
			if (recvState != null && stateCallback != null) {
				recvState = recvState.withMode3FullState(advanced);
				stateCallback.accept(recvState);
			}
		} catch (RuntimeException ignored) {
		}
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
			throw new FormatException();
		}
		int receivedProtocolVersion = ByteUtils.readUint16(streamHeaderPlaintext, 0);
		pcsEnabled = (receivedProtocolVersion & 0x8000) != 0;
		mode2Enabled = (receivedProtocolVersion & 0x4000) != 0;
		mode3Enabled = (receivedProtocolVersion & 0x2000) != 0;
		mode3FullEnabled = MODE3_FULL_ENABLED &&
				(receivedProtocolVersion & MODE3_FULL_STREAM_FLAG) != 0;
		int baseVersion = receivedProtocolVersion & 0x0FFF;
		if (baseVersion != PROTOCOL_VERSION) {
			throw new FormatException();
		}
		if (!pcsEnabled || !mode2Enabled) {
			throw new FormatException();
		}
		long receivedStreamNumber = ByteUtils.readUint64(streamHeaderPlaintext, INT_16_BYTES);
		if (receivedStreamNumber != streamNumber) {
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
