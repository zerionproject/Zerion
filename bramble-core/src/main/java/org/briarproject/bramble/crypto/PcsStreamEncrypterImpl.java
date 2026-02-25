package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamEncrypter;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqChunk;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.bramble.crypto.pcs.PcsHeaderCodec;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PQ_CHUNK;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PQ_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_PROTOCOL_VERSION;
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
class PcsStreamEncrypterImpl implements StreamEncrypter {

	private static final Logger LOG =
			Logger.getLogger(PcsStreamEncrypterImpl.class.getName());

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
	private final PcsHeaderCodec headerCodec;

	private PcsSessionState sendState;
	@Nullable
	private PqRatchetState pqState;
	private long frameNumber;
	private boolean writeTag, writeStreamHeader;

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(out, cipher, ratchet, streamNumber, tag, streamHeaderNonce,
				streamHeaderKey, initialState, stateCallback, null, null, null);
	}

	PcsStreamEncrypterImpl(OutputStream out, AuthenticatedCipher cipher,
			PcsRatchet ratchet, long streamNumber, @Nullable byte[] tag,
			byte[] streamHeaderNonce, SecretKey streamHeaderKey,
			PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable PqRatchet pqRatchet,
			@Nullable PqRatchetState initialPqState,
			@Nullable Consumer<PqRatchetState> pqStateCallback) {
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
		this.headerCodec = new PcsHeaderCodec();
		frameNonce = new byte[FRAME_NONCE_LENGTH];
		frameHeader = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
		framePlaintext = new byte[MAX_PAYLOAD_LENGTH + PCS_MODE3_HEADER_MAX_SIZE];
		frameCiphertext = new byte[MAX_FRAME_LENGTH + PCS_MODE3_HEADER_MAX_SIZE + MAC_LENGTH];
		frameNumber = 0;
		writeTag = (tag != null);
		writeStreamHeader = true;
	}

	@Override
	public void writeFrame(byte[] payload, int payloadLength,
			int paddingLength, boolean finalFrame) throws IOException {
		if (payloadLength < 0 || paddingLength < 0)
			throw new IllegalArgumentException();

		PqChunk pqChunk = null;
		boolean useMode3 = MODE3_ENABLED && sendState.isMode3() &&
				pqRatchet != null && pqState != null;

		int pcsHeaderSize;
		if (useMode3) {
			pqChunk = pqRatchet.getNextChunkToSend(pqState);
			pcsHeaderSize = headerCodec.getMode3HeaderSize(pqChunk);
		} else if (sendState.isMode2()) {
			pcsHeaderSize = PCS_HEADER_MAX_SIZE;
		} else {
			pcsHeaderSize = PCS_HEADER_MIN_SIZE;
		}

		int effectiveMaxPayload = MAX_PAYLOAD_LENGTH - pcsHeaderSize;
		if (payloadLength + paddingLength > effectiveMaxPayload)
			throw new IllegalArgumentException();
		if (frameNumber < 0) throw new IOException();
		if (writeTag) writeTag();
		if (writeStreamHeader) writeStreamHeader();

		PublicKey dhPublicKey = null;
		if (sendState.isMode2()) {
			try {
				DhRatchetResult dhResult = ratchet.performSendDhRatchet(sendState);
				sendState = dhResult.getNewState();
				dhPublicKey = dhResult.getDhPublicKey();
			} catch (GeneralSecurityException | PcsException e) {
				throw new IOException("DH ratchet failed", e);
			}
		}

		AdvanceResult advanceResult = ratchet.advanceSendChain(sendState);
		SecretKey messageKey = advanceResult.getMessageKey();
		int messageNumber = sendState.getMessageNumber();
		int prevChainLength = sendState.getPreviousChainLength();

		sendState = advanceResult.getNewState();

		int totalPayloadLength = payloadLength + pcsHeaderSize;
		FrameEncoder.encodeHeader(frameHeader, finalFrame, totalPayloadLength,
				paddingLength);

		FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
		try {
			cipher.init(true, messageKey, frameNonce);
			int encrypted = cipher.process(frameHeader, 0,
					FRAME_HEADER_PLAINTEXT_LENGTH, frameCiphertext, 0);
			if (encrypted != FRAME_HEADER_LENGTH) throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}

		if (useMode3 && dhPublicKey != null) {
			byte[] header = headerCodec.encodeMode3Header(messageNumber,
					prevChainLength, dhPublicKey.getEncoded(),
					sendState.getPqEpoch(), pqChunk);
			System.arraycopy(header, 0, framePlaintext, 0, header.length);
		} else {
			encodePcsHeader(framePlaintext, messageNumber, prevChainLength,
					dhPublicKey);
		}

		System.arraycopy(payload, 0, framePlaintext, pcsHeaderSize, payloadLength);

		for (int i = 0; i < paddingLength; i++)
			framePlaintext[pcsHeaderSize + payloadLength + i] = 0;

		FrameEncoder.encodeNonce(frameNonce, frameNumber, false);
		try {
			cipher.init(true, messageKey, frameNonce);
			int encrypted = cipher.process(framePlaintext, 0,
					totalPayloadLength + paddingLength, frameCiphertext,
					FRAME_HEADER_LENGTH);
			if (encrypted != totalPayloadLength + paddingLength + MAC_LENGTH)
				throw new RuntimeException();
		} catch (GeneralSecurityException badCipher) {
			throw new RuntimeException(badCipher);
		}

		out.write(frameCiphertext, 0, FRAME_HEADER_LENGTH + totalPayloadLength
				+ paddingLength + MAC_LENGTH);
		frameNumber++;

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
					SecretKey pqSecret = pqRatchet.deriveEpochSecret(pqState);
					SecretKey newRootKey = pqRatchet.mixPqSecretIntoRootKey(
							sendState.getRootKey(), pqSecret);
					sendState = sendState.afterPqRatchet(newRootKey,
							pqState.getCurrentEpoch());
					pqState = pqRatchet.completeEpoch(pqState,
							System.currentTimeMillis());
				} catch (Exception e) {
					LOG.log(WARNING,
							"PQ epoch secret derivation failed, resetting PQ state", e);
					pqState = pqRatchet.initialize(System.currentTimeMillis());
				}
			}
			if (pqStateCallback != null) {
				pqStateCallback.accept(pqState);
			}
		}

		if (stateCallback != null) {
			stateCallback.accept(sendState);
		}
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
		int version = PROTOCOL_VERSION | 0x8000;
		if (sendState.isMode2()) {
			version |= 0x4000;
		}
		if (MODE3_ENABLED && sendState.isMode3()) {
			version |= 0x2000;
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
		if (MODE3_ENABLED && sendState.isMode3()) {
			pcsHeaderSize = PCS_MODE3_HEADER_MAX_SIZE;
		} else if (sendState.isMode2()) {
			pcsHeaderSize = PCS_HEADER_MAX_SIZE;
		} else {
			pcsHeaderSize = PCS_HEADER_MIN_SIZE;
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
		return MODE3_ENABLED && sendState.isMode3();
	}
}
