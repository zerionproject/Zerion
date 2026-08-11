package org.zerionproject.transport.mesh;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.util.ByteUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

/**
 * A frame flooded across the offline mesh (Phase 3). It wraps an opaque payload
 * (a Phase 2 sealed-sender envelope) with the minimum a relaying node needs: a
 * message id for deduplication during a flood, and a remaining hop count that
 * bounds how far it travels. A relay never opens the payload; it only dedups,
 * decrements the hop count, and rebroadcasts.
 *
 * <pre>
 *   0   version      1
 *   1   hopsLeft     1     remaining hops (0 = do not relay further)
 *   2   messageId    16    dedup id, relay-visible
 *   18  payloadLen   4     uint32
 *   22  payload      var   opaque (a sealed-sender envelope)
 * </pre>
 */
@NotNullByDefault
public class MeshFrame {

	public static final int VERSION = 0x01;
	public static final int MESSAGE_ID_BYTES = 16;
	public static final int MAX_HOPS = 7;
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

	private static final int OFF_VERSION = 0;
	private static final int OFF_HOPS = 1;
	private static final int OFF_MESSAGE_ID = 2;
	private static final int OFF_PAYLOAD_LEN =
			OFF_MESSAGE_ID + MESSAGE_ID_BYTES;
	static final int HEADER_BYTES = OFF_PAYLOAD_LEN + ByteUtils.INT_32_BYTES;

	private final int hopsLeft;
	private final byte[] messageId;
	private final byte[] payload;

	public MeshFrame(int hopsLeft, byte[] messageId, byte[] payload) {
		if (messageId.length != MESSAGE_ID_BYTES) {
			throw new IllegalArgumentException("bad message id length");
		}
		if (hopsLeft < 0 || hopsLeft > MAX_HOPS) {
			throw new IllegalArgumentException("bad hop count");
		}
		this.hopsLeft = hopsLeft;
		this.messageId = messageId;
		this.payload = payload;
	}

	public int getHopsLeft() {
		return hopsLeft;
	}

	public byte[] getMessageId() {
		return messageId;
	}

	public byte[] getPayload() {
		return payload;
	}

	/** Returns a copy with the hop count decremented by one, or null if this
	 * frame must not be relayed further. */
	@javax.annotation.Nullable
	public MeshFrame decremented() {
		if (hopsLeft <= 0) return null;
		return new MeshFrame(hopsLeft - 1, messageId, payload);
	}

	public byte[] encode() {
		byte[] out = new byte[HEADER_BYTES + payload.length];
		out[OFF_VERSION] = (byte) VERSION;
		out[OFF_HOPS] = (byte) hopsLeft;
		System.arraycopy(messageId, 0, out, OFF_MESSAGE_ID, MESSAGE_ID_BYTES);
		ByteUtils.writeUint32(payload.length, out, OFF_PAYLOAD_LEN);
		System.arraycopy(payload, 0, out, HEADER_BYTES, payload.length);
		return out;
	}

	public static MeshFrame decode(byte[] in) throws FormatException {
		if (in.length < HEADER_BYTES) throw new FormatException();
		if ((in[OFF_VERSION] & 0xFF) != VERSION) throw new FormatException();
		int hops = in[OFF_HOPS] & 0xFF;
		if (hops > MAX_HOPS) throw new FormatException();
		long payloadLen = ByteUtils.readUint32(in, OFF_PAYLOAD_LEN);
		if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES
				|| in.length != HEADER_BYTES + payloadLen) {
			throw new FormatException();
		}
		byte[] messageId = Arrays.copyOfRange(in, OFF_MESSAGE_ID,
				OFF_MESSAGE_ID + MESSAGE_ID_BYTES);
		byte[] payload = Arrays.copyOfRange(in, HEADER_BYTES, in.length);
		return new MeshFrame(hops, messageId, payload);
	}
}
