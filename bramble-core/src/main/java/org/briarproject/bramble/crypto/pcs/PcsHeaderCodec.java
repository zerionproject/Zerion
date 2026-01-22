package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.DH_PUBLIC_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_DH_RATCHET;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.FLAG_PCS_ENABLED;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MESSAGE_NUMBER_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MAX_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HEADER_MIN_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_PROTOCOL_VERSION;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PREVIOUS_CHAIN_LENGTH_SIZE;

/**
 * Codec for encoding and decoding PCS message headers.
 * <p>
 * Header format (Mode 1, symmetric-only):
 * <pre>
 * +----------+-------+---------------+--------------------+
 * | Version  | Flags | Message Num   | Prev Chain Length  |
 * | (1 byte) | (1 b) | (4 bytes, BE) | (4 bytes, BE)      |
 * +----------+-------+---------------+--------------------+
 * </pre>
 * <p>
 * Header format (Mode 2, with DH ratchet):
 * <pre>
 * +----------+-------+---------------+--------------------+------------------+
 * | Version  | Flags | Message Num   | Prev Chain Length  | DH Public Key    |
 * | (1 byte) | (1 b) | (4 bytes, BE) | (4 bytes, BE)      | (32 bytes)       |
 * +----------+-------+---------------+--------------------+------------------+
 * </pre>
 */
@Immutable
@NotNullByDefault
public class PcsHeaderCodec {

	/**
	 * Decoded PCS header information.
	 */
	public static class PcsHeader {
		private final int version;
		private final byte flags;
		private final int messageNumber;
		private final int previousChainLength;
		private final byte[] dhPublicKey; // null for Mode 1

		public PcsHeader(int version, byte flags, int messageNumber,
				int previousChainLength, byte[] dhPublicKey) {
			this.version = version;
			this.flags = flags;
			this.messageNumber = messageNumber;
			this.previousChainLength = previousChainLength;
			this.dhPublicKey = dhPublicKey;
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

		public int getMessageNumber() {
			return messageNumber;
		}

		public int getPreviousChainLength() {
			return previousChainLength;
		}

		public byte[] getDhPublicKey() {
			return dhPublicKey;
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

	/**
	 * Decodes a PCS header from bytes.
	 *
	 * @param data The header bytes
	 * @return The decoded header
	 * @throws PcsException If the header is invalid
	 */
	public PcsHeader decode(byte[] data) throws PcsException {
		if (data.length < PCS_HEADER_MIN_SIZE) {
			throw new PcsException(
					"PCS header too short: " + data.length + " bytes " +
					"(minimum: " + PCS_HEADER_MIN_SIZE + ")");
		}

		int offset = 0;

		// Version (1 byte)
		int version = data[offset++] & 0xFF;
		if (version != PCS_PROTOCOL_VERSION) {
			throw new PcsException(
					"Unsupported PCS version: " + version +
					" (expected: " + PCS_PROTOCOL_VERSION + ")");
		}

		// Flags (1 byte)
		byte flags = data[offset++];

		// Message number (4 bytes, big-endian)
		int messageNumber = readUint32(data, offset);
		offset += MESSAGE_NUMBER_SIZE;

		// Previous chain length (4 bytes, big-endian)
		int previousChainLength = readUint32(data, offset);
		offset += PREVIOUS_CHAIN_LENGTH_SIZE;

		// DH public key (32 bytes, optional)
		byte[] dhPublicKey = null;
		if ((flags & FLAG_DH_RATCHET) != 0) {
			if (data.length < PCS_HEADER_MAX_SIZE) {
				throw new PcsException(
						"PCS header with DH flag too short: " + data.length +
						" bytes (expected: " + PCS_HEADER_MAX_SIZE + ")");
			}
			dhPublicKey = new byte[DH_PUBLIC_KEY_SIZE];
			System.arraycopy(data, offset, dhPublicKey, 0, DH_PUBLIC_KEY_SIZE);
		}

		return new PcsHeader(version, flags, messageNumber, previousChainLength,
				dhPublicKey);
	}

	/**
	 * Returns the size of the header based on flags.
	 *
	 * @param hasDhRatchet Whether the header includes a DH public key
	 * @return The header size in bytes
	 */
	public int getHeaderSize(boolean hasDhRatchet) {
		return hasDhRatchet ? PCS_HEADER_MAX_SIZE : PCS_HEADER_MIN_SIZE;
	}

	// ==================== Helper Methods ====================

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
