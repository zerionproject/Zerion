package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PqChunk;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PQ_CHUNK;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PQ_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MESSAGE_NUMBER_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MODE3_HEADER_MIN_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_PROTOCOL_VERSION;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PQ_CHUNK_HEADER_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PQ_CHUNK_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PQ_EPOCH_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PREVIOUS_CHAIN_LENGTH_SIZE;

/**
 * Codec for encoding and decoding PCS message headers.
 */
@Immutable
@NotNullByDefault
public class PcsHeaderCodec {

	public static class PcsHeader {
		private final int version;
		private final byte flags;
		private final int messageNumber;
		private final int previousChainLength;
		@Nullable
		private final byte[] dhPublicKey;
		private final long pqEpoch;
		@Nullable
		private final PqChunk pqChunk;

		public PcsHeader(int version, byte flags, int messageNumber,
				int previousChainLength, @Nullable byte[] dhPublicKey) {
			this(version, flags, messageNumber, previousChainLength,
					dhPublicKey, 0, null);
		}

		public PcsHeader(int version, byte flags, int messageNumber,
				int previousChainLength, @Nullable byte[] dhPublicKey,
				long pqEpoch, @Nullable PqChunk pqChunk) {
			this.version = version;
			this.flags = flags;
			this.messageNumber = messageNumber;
			this.previousChainLength = previousChainLength;
			this.dhPublicKey = dhPublicKey;
			this.pqEpoch = pqEpoch;
			this.pqChunk = pqChunk;
		}

		public int getVersion() {
			return version;
		}

		public byte getFlags() {
			return flags;
		}

		public boolean isPcsEnabled() {
			return (flags & FLAG_PCS_ENABLED) != 0;
		}

		public boolean hasDhRatchet() {
			return (flags & FLAG_DH_RATCHET) != 0;
		}

		public boolean isPqEnabled() {
			return (flags & FLAG_PQ_ENABLED) != 0;
		}

		public boolean hasPqChunk() {
			return (flags & FLAG_PQ_CHUNK) != 0;
		}

		public int getMessageNumber() {
			return messageNumber;
		}

		public int getPreviousChainLength() {
			return previousChainLength;
		}

		@Nullable
		public byte[] getDhPublicKey() {
			return dhPublicKey;
		}

		public long getPqEpoch() {
			return pqEpoch;
		}

		@Nullable
		public PqChunk getPqChunk() {
			return pqChunk;
		}
	}

	/**
	 * Encodes a Mode 1 (symmetric-only) PCS header.
	 *
	 * @param messageNumber The message number in the current chain
	 * @param previousChainLength The length of the previous chain
	 * @return The encoded header bytes (10 bytes)
	 */
	public byte[] encodeMode1Header(int messageNumber, int previousChainLength) {
		byte[] header = new byte[PCS_HEADER_MIN_SIZE];
		int offset = 0;

		// Version (1 byte)
		header[offset++] = (byte) PCS_PROTOCOL_VERSION;

		// Flags (1 byte) - PCS enabled, no DH ratchet
		header[offset++] = FLAG_PCS_ENABLED;

		// Message number (4 bytes, big-endian)
		writeUint32(messageNumber, header, offset);
		offset += MESSAGE_NUMBER_SIZE;

		// Previous chain length (4 bytes, big-endian)
		writeUint32(previousChainLength, header, offset);

		return header;
	}

	/**
	 * Encodes a Mode 2 (with DH ratchet) PCS header.
	 *
	 * @param messageNumber The message number in the current chain
	 * @param previousChainLength The length of the previous chain
	 * @param dhPublicKey The DH public key for this message (32 bytes)
	 * @return The encoded header bytes (42 bytes)
	 */
	public byte[] encodeMode2Header(int messageNumber, int previousChainLength,
			byte[] dhPublicKey) {
		if (dhPublicKey.length != DH_PUBLIC_KEY_SIZE) {
			throw new IllegalArgumentException(
					"DH public key must be " + DH_PUBLIC_KEY_SIZE + " bytes");
		}

		byte[] header = new byte[PCS_HEADER_MAX_SIZE];
		int offset = 0;

		// Version (1 byte)
		header[offset++] = (byte) PCS_PROTOCOL_VERSION;

		// Flags (1 byte) - PCS enabled + DH ratchet present
		header[offset++] = (byte) (FLAG_PCS_ENABLED | FLAG_DH_RATCHET);

		// Message number (4 bytes, big-endian)
		writeUint32(messageNumber, header, offset);
		offset += MESSAGE_NUMBER_SIZE;

		// Previous chain length (4 bytes, big-endian)
		writeUint32(previousChainLength, header, offset);
		offset += PREVIOUS_CHAIN_LENGTH_SIZE;

		// DH public key (32 bytes)
		System.arraycopy(dhPublicKey, 0, header, offset, DH_PUBLIC_KEY_SIZE);

		return header;
	}

	public byte[] encodeMode3Header(int messageNumber, int previousChainLength,
			byte[] dhPublicKey, long pqEpoch, @Nullable PqChunk pqChunk) {
		if (!MODE3_ENABLED) {
			throw new IllegalStateException("Mode 3 not enabled");
		}
		if (dhPublicKey.length != DH_PUBLIC_KEY_SIZE) {
			throw new IllegalArgumentException("Invalid DH key size");
		}

		int headerSize = PCS_MODE3_HEADER_MIN_SIZE;
		byte flags = (byte) (FLAG_PCS_ENABLED | FLAG_DH_RATCHET | FLAG_PQ_ENABLED);

		if (pqChunk != null) {
			flags |= FLAG_PQ_CHUNK;
			headerSize = PCS_MODE3_HEADER_MIN_SIZE + PQ_CHUNK_HEADER_SIZE +
					pqChunk.getLength();
		}

		byte[] header = new byte[headerSize];
		int offset = 0;

		header[offset++] = (byte) PCS_PROTOCOL_VERSION;
		header[offset++] = flags;

		writeUint32(messageNumber, header, offset);
		offset += MESSAGE_NUMBER_SIZE;

		writeUint32(previousChainLength, header, offset);
		offset += PREVIOUS_CHAIN_LENGTH_SIZE;

		System.arraycopy(dhPublicKey, 0, header, offset, DH_PUBLIC_KEY_SIZE);
		offset += DH_PUBLIC_KEY_SIZE;

		writeUint32((int) pqEpoch, header, offset);
		offset += PQ_EPOCH_SIZE;

		if (pqChunk != null) {
			header[offset++] = pqChunk.getType();
			header[offset++] = (byte) pqChunk.getIndex();
			writeUint16(pqChunk.getLength(), header, offset);
			offset += 2;
			System.arraycopy(pqChunk.getData(), 0, header, offset,
					pqChunk.getLength());
		}

		return header;
	}

	public PcsHeader decode(byte[] data) throws PcsException {
		if (data.length < PCS_HEADER_MIN_SIZE) {
			throw new PcsException("Header too short");
		}

		int offset = 0;

		int version = data[offset++] & 0xFF;
		if (version != PCS_PROTOCOL_VERSION) {
			throw new PcsException("Unsupported version: " + version);
		}

		byte flags = data[offset++];

		int messageNumber = readUint32(data, offset);
		offset += MESSAGE_NUMBER_SIZE;

		int previousChainLength = readUint32(data, offset);
		offset += PREVIOUS_CHAIN_LENGTH_SIZE;

		byte[] dhPublicKey = null;
		if ((flags & FLAG_DH_RATCHET) != 0) {
			if (data.length < PCS_HEADER_MAX_SIZE) {
				throw new PcsException("DH header too short");
			}
			dhPublicKey = new byte[DH_PUBLIC_KEY_SIZE];
			System.arraycopy(data, offset, dhPublicKey, 0, DH_PUBLIC_KEY_SIZE);
			offset += DH_PUBLIC_KEY_SIZE;
		}

		long pqEpoch = 0;
		PqChunk pqChunk = null;

		if ((flags & FLAG_PQ_ENABLED) != 0) {
			if (!MODE3_ENABLED) {
				throw new PcsException("Mode 3 not enabled");
			}
			if (data.length < offset + PQ_EPOCH_SIZE) {
				throw new PcsException("PQ header too short");
			}
			pqEpoch = readUint32(data, offset) & 0xFFFFFFFFL;
			offset += PQ_EPOCH_SIZE;

			if ((flags & FLAG_PQ_CHUNK) != 0) {
				if (data.length < offset + PQ_CHUNK_HEADER_SIZE) {
					throw new PcsException("PQ chunk header too short");
				}
				byte chunkType = data[offset++];
				int chunkIndex = data[offset++] & 0xFF;
				int chunkLength = readUint16(data, offset);
				offset += 2;

				if (chunkLength > PQ_CHUNK_SIZE) {
					throw new PcsException("PQ chunk too large");
				}
				if (data.length < offset + chunkLength) {
					throw new PcsException("PQ chunk data too short");
				}

				byte[] chunkData = new byte[chunkLength];
				System.arraycopy(data, offset, chunkData, 0, chunkLength);
				pqChunk = new PqChunk(chunkType, chunkIndex, chunkData);
			}
		}

		return new PcsHeader(version, flags, messageNumber, previousChainLength,
				dhPublicKey, pqEpoch, pqChunk);
	}

	public int getHeaderSize(boolean hasDhRatchet) {
		return hasDhRatchet ? PCS_HEADER_MAX_SIZE : PCS_HEADER_MIN_SIZE;
	}

	public int getMode3HeaderSize(@Nullable PqChunk pqChunk) {
		if (!MODE3_ENABLED) {
			throw new IllegalStateException("Mode 3 not enabled");
		}
		if (pqChunk == null) {
			return PCS_MODE3_HEADER_MIN_SIZE;
		}
		return PCS_MODE3_HEADER_MIN_SIZE + PQ_CHUNK_HEADER_SIZE +
				pqChunk.getLength();
	}

	// ==================== Helper Methods ====================

	private static void writeUint16(int value, byte[] dest, int offset) {
		dest[offset] = (byte) (value >> 8);
		dest[offset + 1] = (byte) value;
	}

	private static int readUint16(byte[] src, int offset) {
		return ((src[offset] & 0xFF) << 8) | (src[offset + 1] & 0xFF);
	}

	private static void writeUint32(int value, byte[] dest, int offset) {
		dest[offset] = (byte) (value >> 24);
		dest[offset + 1] = (byte) (value >> 16);
		dest[offset + 2] = (byte) (value >> 8);
		dest[offset + 3] = (byte) value;
	}

	private static int readUint32(byte[] src, int offset) {
		return ((src[offset] & 0xFF) << 24) |
				((src[offset + 1] & 0xFF) << 16) |
				((src[offset + 2] & 0xFF) << 8) |
				(src[offset + 3] & 0xFF);
	}
}
