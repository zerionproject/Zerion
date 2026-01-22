package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamDecrypter;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.AdvanceResult;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet.DhRatchetResult;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
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
	private PcsSessionState recvState;
	private long frameNumber;
	private boolean finalFrame;
	private boolean pcsEnabled;
	private boolean mode2Enabled;
	private boolean streamHeaderRead;
	@Nullable
	private PublicKey lastReceivedDhKey;

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback) {
		this(in, cipher, ratchet, skippedKeyStore, chainId, streamNumber,
				streamHeaderKey, initialState, stateCallback, null);
	}

	PcsStreamDecrypterImpl(InputStream in, AuthenticatedCipher cipher,
			PcsRatchet ratchet, SkippedKeyStore skippedKeyStore,
			byte[] chainId, long streamNumber, SecretKey streamHeaderKey,
			@Nullable PcsSessionState initialState,
			@Nullable Consumer<PcsSessionState> stateCallback,
			@Nullable KeyParser keyParser) {
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
		frameNonce = new byte[FRAME_NONCE_LENGTH];
		frameHeader = new byte[FRAME_HEADER_PLAINTEXT_LENGTH];
		frameCiphertext = new byte[MAX_FRAME_LENGTH + PCS_HEADER_MAX_SIZE + MAC_LENGTH];
		pcsHeaderBuffer = new byte[PCS_HEADER_MAX_SIZE];
		frameNumber = 0;
		finalFrame = false;
		pcsEnabled = false;
		mode2Enabled = false;
		streamHeaderRead = false;
		lastReceivedDhKey = null;
	}

	@Override
	public int readFrame(byte[] payload) throws IOException {
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
		try {
			AdvanceResult advanceResult = ratchet.advanceReceiveChain(
					recvState, expectedMsgNum, skippedKeyStore);
			messageKey = advanceResult.getMessageKey();
			messageNumber = expectedMsgNum;

			FrameEncoder.encodeNonce(frameNonce, frameNumber, true);
			cipher.init(false, messageKey, frameNonce);
			int decrypted = cipher.process(frameCiphertext, 0,
					FRAME_HEADER_LENGTH, frameHeader, 0);
			if (decrypted != FRAME_HEADER_PLAINTEXT_LENGTH)
				throw new RuntimeException();

			recvState = advanceResult.getNewState();
		} catch (GeneralSecurityException | PcsException e) {
			throw new FormatException();
		}

		finalFrame = FrameEncoder.isFinalFrame(frameHeader);
		int totalPayloadLength = FrameEncoder.getPayloadLength(frameHeader);
		int paddingLength = FrameEncoder.getPaddingLength(frameHeader);

		if (totalPayloadLength < PCS_HEADER_MIN_SIZE)
			throw new FormatException();
		if (totalPayloadLength + paddingLength > MAX_PAYLOAD_LENGTH + PCS_HEADER_MAX_SIZE)
			throw new FormatException();

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
			throw new FormatException();
		}

		System.arraycopy(decryptedPayload, 0, pcsHeaderBuffer, 0, PCS_HEADER_MIN_SIZE);
		int pcsVersion = pcsHeaderBuffer[0] & 0xFF;
		int pcsFlags = pcsHeaderBuffer[1] & 0xFF;
		int receivedMsgNum = (int) ByteUtils.readUint32(pcsHeaderBuffer, 2);

		if (pcsVersion != 1) throw new FormatException();
		if ((pcsFlags & FLAG_PCS_ENABLED) == 0) throw new FormatException();
		if (receivedMsgNum != messageNumber) throw new FormatException();

		boolean hasDhKey = (pcsFlags & FLAG_DH_RATCHET) != 0;
		int pcsHeaderSize = hasDhKey ? PCS_HEADER_MAX_SIZE : PCS_HEADER_MIN_SIZE;

		if (totalPayloadLength < pcsHeaderSize)
			throw new FormatException();

		if (hasDhKey) {
			byte[] dhKeyBytes = new byte[DH_PUBLIC_KEY_SIZE];
			System.arraycopy(decryptedPayload, PCS_HEADER_MIN_SIZE,
					dhKeyBytes, 0, DH_PUBLIC_KEY_SIZE);

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

		int actualPayloadLength = totalPayloadLength - pcsHeaderSize;
		System.arraycopy(decryptedPayload, pcsHeaderSize, payload, 0, actualPayloadLength);

		for (int i = 0; i < paddingLength; i++) {
			if (decryptedPayload[totalPayloadLength + i] != 0)
				throw new FormatException();
		}

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
			throw new FormatException();
		}
		int receivedProtocolVersion = ByteUtils.readUint16(streamHeaderPlaintext, 0);
		pcsEnabled = (receivedProtocolVersion & 0x8000) != 0;
		mode2Enabled = (receivedProtocolVersion & 0x4000) != 0;
		int baseVersion = receivedProtocolVersion & 0x3FFF;
		if (baseVersion != PROTOCOL_VERSION)
			throw new FormatException();
		if (!pcsEnabled) {
			throw new FormatException();
		}
		long receivedStreamNumber = ByteUtils.readUint64(streamHeaderPlaintext, INT_16_BYTES);
		if (receivedStreamNumber != streamNumber) throw new FormatException();
		byte[] chainKeyBytes = new byte[SecretKey.LENGTH];
		System.arraycopy(streamHeaderPlaintext, INT_16_BYTES + INT_64_BYTES,
				chainKeyBytes, 0, SecretKey.LENGTH);
		SecretKey chainKey = new SecretKey(chainKeyBytes);

		if (mode2Enabled) {
			recvState = ratchet.initializeMode2AsInitiator(chainKey);
		} else {
			recvState = PcsSessionState.createInitial(chainKey);
		}
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
		streamHeaderRead = true;
	}

	@Nullable
	public PcsSessionState getState() {
		return recvState;
	}

	public boolean isPcsEnabled() {
		return pcsEnabled;
	}

	public boolean isMode2() {
		return mode2Enabled;
	}
}
