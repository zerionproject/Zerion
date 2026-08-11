package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqChunk;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.crypto.pcs.PcsHeaderCodec;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import org.zerionproject.core.crypto.pcs.PcsPersistenceException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_ENABLED;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_FRAME_OVERHEAD;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_STREAM_FLAG;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MAX_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MIN_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_PROTOCOL_VERSION;
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
class PcsStreamEncrypterImpl implements StreamEncrypter {

	private final OutputStream out;
	private final AuthenticatedCipher cipher;
	private final PcsRatchet ratchet;
	private final SecretKey streamHeaderKey;
	private final long streamNumber;
	@Nullable
	private final byte[] tag;
	private final byte[] streamHeaderNonce;
	private final byte[] frameNonce, frameHeader;
	private final byte[] framePlaintext, frameCiphertext;
	@Nullable
	private final Consumer<PcsSessionState> stateCallback;
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

	private PcsSessionState sendState;
	@Nullable
	private PqRatchetState pqState;
	private long frameNumber;
	private boolean writeTag, writeStreamHeader;
	private SecretKey streamChainKey;
	private int streamMessageNumber;
	@Nullable
	private Boolean streamUseMode3FullLocked;

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(out, cipher, ratchet, streamNumber, tag, streamHeaderNonce,
				streamHeaderKey, initialState, stateCallback, null, null,
				null, null, null, null, null, null);
	}

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback) {
		this(out, cipher, ratchet, streamNumber, tag, streamHeaderNonce,
				streamHeaderKey, initialState, stateCallback, pqRatchet,
				initialPqState, pqStateCallback, null, null, null, null,
				null);
	}

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback,
			@Nullable Consumer<SecretKey> pqCrossMixCallback) {
		this(out, cipher, ratchet, streamNumber, tag, streamHeaderNonce,
				streamHeaderKey, initialState, stateCallback, pqRatchet,
				initialPqState, pqStateCallback, pqCrossMixCallback, null,
				null, null, null);
	}

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback,
			@Nullable Consumer<SecretKey> pqCrossMixCallback,
			@Nullable Mode3FullRatchet mode3FullRatchet,
			@Nullable java.util.function.Supplier<Mode3FullState>
					mode3FullStateRefresher) {
		this(out, cipher, ratchet, streamNumber, tag, streamHeaderNonce,
				streamHeaderKey, initialState, stateCallback, pqRatchet,
				initialPqState, pqStateCallback, pqCrossMixCallback,
				mode3FullRatchet, mode3FullStateRefresher, null, null);
	}

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
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
		this.out = out;
		this.cipher = cipher;
		this.ratchet = ratchet;
		this.streamNumber = streamNumber;
		this.tag = tag;
		this.streamHeaderNonce = streamHeaderNonce;
		this.streamHeaderKey = streamHeaderKey;
		this.sendState = initialState;
		this.stateCallback = stateCallback;
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
		framePlaintext = new byte[MAX_PAYLOAD_LENGTH + maxHeader];
		frameCiphertext = new byte[MAX_FRAME_LENGTH + maxHeader + MAC_LENGTH];
		frameNumber = 0;
		writeTag = (tag != null);
		writeStreamHeader = true;
		SecretKey rootKey = initialState.getRootKey();
		if (rootKey == null) rootKey = initialState.getChainKey();
		this.streamChainKey = ratchet.deriveStreamInitialChainKey(rootKey,
				streamNumber);
		this.streamMessageNumber = 0;
	}

	@Override
	public void writeFrame(byte[] payload, int payloadLength,
			int paddingLength, boolean finalFrame) throws IOException {
		if (payloadLength < 0 || paddingLength < 0)
			throw new IllegalArgumentException();
		if (frameNumber < 0) throw new IOException();

		SecretKey classicalMessageKey;
		SecretKey bodyMessageKey;
		int messageNumber;
		int prevChainLength;
		PublicKey dhPublicKey = null;
		Mode3FullRatchet.PqSendResult mode3FullSend = null;
		boolean useMode3Full;
		boolean useMode3;
		int pcsHeaderSize;
		long pqEpoch;
		PqChunk pqChunk = null;

		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();

		if (directionLock != null) directionLock.lock();
		try {
			if (sessionStateRefresher != null) {
				PcsSessionState fresh = sessionStateRefresher.get();
				if (fresh != null) {
					sendState = fresh;
				}
			}

			if (streamUseMode3FullLocked != null) {
				useMode3Full = streamUseMode3FullLocked;
			} else {
				useMode3Full = MODE3_FULL_ENABLED && sendState.isMode3Full() &&
						mode3FullRatchet != null;
				streamUseMode3FullLocked = useMode3Full;
			}
			useMode3 = !useMode3Full && sendState.isMode3()
					&& pqRatchet != null && pqState != null;

			if (useMode3Full) {
				pcsHeaderSize = headerCodec.getMode3FullHeaderSize();
			} else if (useMode3) {
				pqChunk = pqRatchet.getNextChunkToSend(pqState);
				pcsHeaderSize = headerCodec.getMode3HeaderSize(pqChunk);
			} else {
				pcsHeaderSize = PCS_HEADER_MAX_SIZE;
			}

			int effectiveMaxPayload = MAX_PAYLOAD_LENGTH - pcsHeaderSize;
			if (payloadLength + paddingLength > effectiveMaxPayload)
				throw new IllegalArgumentException();

			if (sendState.isMode2()) {
				try {
					DhRatchetResult dhResult = ratchet
							.performSendDhRatchet(sendState);
					sendState = dhResult.getNewState();
					dhPublicKey = dhResult.getDhPublicKey();
				} catch (GeneralSecurityException | PcsException e) {
					throw new IOException("DH ratchet failed", e);
				}
			}

			PcsRatchet.KdfCkResult streamKdf = ratchet.kdfCk(streamChainKey);
			SecretKey messageKey = streamKdf.getMessageKey();
			streamChainKey = streamKdf.getNewChainKey();
			messageNumber = streamMessageNumber;
			streamMessageNumber++;
			prevChainLength = 0;

			classicalMessageKey = messageKey;
			bodyMessageKey = messageKey;
			if (useMode3Full) {
				Mode3FullState m3fState = sendState.getMode3FullState();
				if (m3fState == null) {
					throw new IOException("Mode 3-Full state missing");
				}
				if (mode3FullStateRefresher != null) {
					Mode3FullState fresh = mode3FullStateRefresher.get();
					if (fresh != null) {
						long mergedCounter = Math.max(
								fresh.getMessageCounter(),
								m3fState.getMessageCounter());
						m3fState = new Mode3FullState(
								fresh.getTheirActivePqPk(),
								fresh.getOurActiveKeyPair(),
								fresh.getRecentKeyPairs(),
								mergedCounter);
						sendState = sendState.withMode3FullState(m3fState);
					}
				}
				mode3FullSend = mode3FullRatchet.pqEncapsulateSend(m3fState);
				sendState = sendState.withMode3FullState(
						mode3FullSend.getNewState());
				byte[] ss = mode3FullSend.getSharedSecret();
				if (ss != null) {
					bodyMessageKey = mode3FullRatchet.deriveHybridMessageKey(
							classicalMessageKey, ss);
					java.util.Arrays.fill(ss, (byte) 0);
				}
			}

			pqEpoch = sendState.getPqEpoch();

			if (useMode3 && pqRatchet != null && pqState != null) {
				if (pqChunk != null) {
					pqState = pqRatchet.processChunkSent(pqState);
				}
				pqState = pqRatchet.incrementMessageCount(pqState);
				if (pqRatchet.shouldStartNewEpoch(pqState,
						System.currentTimeMillis())) {
					pqState = pqRatchet.startEpochAsInitiator(pqState);
				}
				if (pqRatchet.isEpochComplete(pqState) &&
						sendState.getRootKey() != null) {
					try {
						SecretKey pqSecret = pqRatchet
								.deriveEpochSecret(pqState);
						SecretKey newRootKey = pqRatchet
								.mixPqSecretIntoRootKey(
										sendState.getRootKey(), pqSecret);
						sendState = sendState.afterPqRatchet(newRootKey,
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

			if (stateCallback != null) {
				try {
					stateCallback.accept(sendState);
				} catch (PcsPersistenceException __pe) {
					throw new IOException("Ratchet state persistence failed", __pe);
				}
			}
		} finally {
			if (directionLock != null) directionLock.unlock();
		}

		int totalPayloadLength = payloadLength + pcsHeaderSize;
		FrameEncoder.encodeHeader(frameHeader, finalFrame, totalPayloadLength,
				paddingLength);

		if (useMode3Full) {
			FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 0);
		} else {
			FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
		}
		try {
			cipher.init(true, classicalMessageKey, frameNonce);
			int encrypted = cipher.process(frameHeader, 0,
					FRAME_HEADER_PLAINTEXT_LENGTH, frameCiphertext, 0);
			if (encrypted != FRAME_HEADER_LENGTH) throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}

		if (useMode3Full && (dhPublicKey == null || mode3FullSend == null)) {
			throw new IOException(
					"Mode3Full frame requires DH key and PQ send state");
		}
		if (useMode3Full && dhPublicKey != null && mode3FullSend != null) {
			org.zerionproject.core.api.crypto.pcs.KpId kpIdUsed =
					mode3FullSend.getKpIdUsed();
			byte[] kpIdBytes = kpIdUsed != null ? kpIdUsed.getBytes()
					: new byte[org.zerionproject.core.api.crypto.pcs
							.PcsConstants.MODE3_FULL_KP_ID_SIZE];
			byte[] header = headerCodec.encodeMode3FullHeader(messageNumber,
					prevChainLength, dhPublicKey.getEncoded(),
					mode3FullSend.getPkAdvertise(),
					mode3FullSend.getCiphertext(),
					kpIdBytes);
			System.arraycopy(header, 0, framePlaintext, 0, header.length);
		} else if (useMode3 && dhPublicKey != null) {
			byte[] header = headerCodec.encodeMode3Header(messageNumber,
					prevChainLength, dhPublicKey.getEncoded(),
					pqEpoch, pqChunk);
			System.arraycopy(header, 0, framePlaintext, 0, header.length);
		} else {
			encodePcsHeader(framePlaintext, messageNumber, prevChainLength,
					dhPublicKey);
		}

		System.arraycopy(payload, 0, framePlaintext, pcsHeaderSize,
				payloadLength);

		for (int i = 0; i < paddingLength; i++)
			framePlaintext[pcsHeaderSize + payloadLength + i] = 0;

		int outOffset;
		if (useMode3Full) {
			FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 1);
			try {
				cipher.init(true, classicalMessageKey, frameNonce);
				int encrypted = cipher.process(framePlaintext, 0,
						pcsHeaderSize, frameCiphertext, FRAME_HEADER_LENGTH);
				if (encrypted != pcsHeaderSize + MAC_LENGTH)
					throw new RuntimeException();
			} catch (GeneralSecurityException badCipher) {
				throw new RuntimeException(badCipher);
			}
			outOffset = FRAME_HEADER_LENGTH + pcsHeaderSize + MAC_LENGTH;

			FrameEncoder.encodeNonceM3F(frameNonce, frameNumber, 2);
			try {
				cipher.init(true, bodyMessageKey, frameNonce);
				int encrypted = cipher.process(framePlaintext, pcsHeaderSize,
						payloadLength + paddingLength, frameCiphertext,
						outOffset);
				if (encrypted != payloadLength + paddingLength + MAC_LENGTH)
					throw new RuntimeException();
			} catch (GeneralSecurityException badCipher) {
				throw new RuntimeException(badCipher);
			}
			outOffset += payloadLength + paddingLength + MAC_LENGTH;
		} else {
			FrameEncoder.encodeNonce(frameNonce, frameNumber, false);
			try {
				cipher.init(true, classicalMessageKey, frameNonce);
				int encrypted = cipher.process(framePlaintext, 0,
						totalPayloadLength + paddingLength, frameCiphertext,
						FRAME_HEADER_LENGTH);
				if (encrypted != totalPayloadLength + paddingLength
						+ MAC_LENGTH)
					throw new RuntimeException();
			} catch (GeneralSecurityException badCipher) {
				throw new RuntimeException(badCipher);
			}
			outOffset = FRAME_HEADER_LENGTH + totalPayloadLength
					+ paddingLength + MAC_LENGTH;
		}

		out.write(frameCiphertext, 0, outOffset);
		frameNumber++;
		if (bodyMessageKey != null && bodyMessageKey != classicalMessageKey) {
			bodyMessageKey.clear();
		}
		if (classicalMessageKey != null) classicalMessageKey.clear();
	}

	private void encodePcsHeader(byte[] dest, int messageNumber,
			int prevChainLength, @Nullable PublicKey dhPublicKey) {
		dest[0] = (byte) PCS_PROTOCOL_VERSION;
		byte flags = FLAG_PCS_ENABLED;
		if (dhPublicKey != null) {
			flags |= FLAG_DH_RATCHET;
		}
		dest[1] = flags;
		ByteUtils.writeUint32(messageNumber, dest, 2);
		ByteUtils.writeUint32(prevChainLength, dest, 6);

		if (dhPublicKey != null) {
			byte[] keyBytes = dhPublicKey.getEncoded();
			if (keyBytes.length != DH_PUBLIC_KEY_SIZE) {
				throw new RuntimeException("Invalid DH key size: " + keyBytes.length);
			}
			System.arraycopy(keyBytes, 0, dest, PCS_HEADER_MIN_SIZE, DH_PUBLIC_KEY_SIZE);
		}
	}

	private void writeTag() throws IOException {
		if (tag == null) throw new IllegalStateException();
		out.write(tag, 0, tag.length);
		writeTag = false;
	}

	private void writeStreamHeader() throws IOException {
		byte[] streamHeaderPlaintext = new byte[STREAM_HEADER_PLAINTEXT_LENGTH];
		int version = PROTOCOL_VERSION | 0x8000 | 0x4000;
		if (sendState.isMode3()) {
			version |= 0x2000;
		}
		if (MODE3_FULL_ENABLED && sendState.isMode3Full()
				&& mode3FullRatchet != null) {
			version |= MODE3_FULL_STREAM_FLAG;
		}
		ByteUtils.writeUint16(version, streamHeaderPlaintext, 0);
		ByteUtils.writeUint64(streamNumber, streamHeaderPlaintext, INT_16_BYTES);
		System.arraycopy(sendState.getChainKey().getBytes(), 0,
				streamHeaderPlaintext, INT_16_BYTES + INT_64_BYTES,
				SecretKey.LENGTH);
		byte[] streamHeaderCiphertext = new byte[STREAM_HEADER_LENGTH];
		System.arraycopy(streamHeaderNonce, 0, streamHeaderCiphertext, 0,
				STREAM_HEADER_NONCE_LENGTH);
		try {
			cipher.init(true, streamHeaderKey, streamHeaderNonce);
			int encrypted = cipher.process(streamHeaderPlaintext, 0,
					STREAM_HEADER_PLAINTEXT_LENGTH, streamHeaderCiphertext,
					STREAM_HEADER_NONCE_LENGTH);
			if (encrypted != STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}
		out.write(streamHeaderCiphertext);
		writeStreamHeader = false;
	}

	@Override
	public int getMaxPayloadLength() {
		int pcsHeaderSize;
		if (MODE3_FULL_ENABLED && sendState.isMode3Full()
				&& mode3FullRatchet != null) {
			pcsHeaderSize = headerCodec.getMode3FullHeaderSize();
		} else if (sendState.isMode3()) {
			pcsHeaderSize = PCS_MODE3_HEADER_MAX_SIZE;
		} else {
			pcsHeaderSize = PCS_HEADER_MAX_SIZE;
		}
		return MAX_PAYLOAD_LENGTH - pcsHeaderSize;
	}

	@Override
	public void flush() throws IOException {
		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();
		out.flush();
	}

	public PcsSessionState getState() {
		return sendState;
	}

	@Nullable
	public PqRatchetState getPqState() {
		return pqState;
	}

	public boolean isMode2() {
		return sendState.isMode2();
	}

	public boolean isMode3() {
		return sendState.isMode3();
	}
}
