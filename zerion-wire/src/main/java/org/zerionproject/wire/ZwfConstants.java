package org.zerionproject.wire;

/**
 * Wire constants for ZWF, the Zerion 3.0 fixed-size framing layer.
 *
 * <p>Every on-wire frame is exactly {@link #FRAME_LENGTH} bytes. The payload is
 * always padded up to that size, so a network observer without the session key
 * sees only a stream of identically-sized, uniformly-random-looking frames —
 * real message, cover and control frames are indistinguishable.
 *
 * <p>Zerion 3.0 uses Mode 3-Full as its one and only ratchet: every frame
 * carries fresh per-message ML-KEM material (public key + ciphertext) inside a
 * three-segment AEAD structure (frame header, Mode 3-Full header, body). The
 * frame is sized to hold that structure plus a useful text payload; larger
 * payloads (media) fragment across frames. The actual Mode 3-Full header size is
 * obtained from the header codec at runtime, so the maximum payload is computed
 * by the stream encrypter rather than hard-coded here.
 */
public interface ZwfConstants {

	/** ZWF wire version, negotiated at the (native) handshake, never sent in a frame. */
	int WIRE_VERSION = 1;

	/**
	 * Fixed on-wire frame size. Sized to hold the 3-segment Mode 3-Full frame
	 * (frame header + ~2.3 KB Mode 3-Full header + body) plus room for a text
	 * payload; media fragments across frames.
	 */
	int FRAME_LENGTH = 4096;

	/** Poly1305 authentication tag length (one per AEAD segment). */
	int MAC_LENGTH = 16;

	/** XSalsa20 nonce length. */
	int NONCE_LENGTH = 24;

	/** Plaintext length of the per-frame header segment (segment 0). */
	int FRAME_HEADER_PLAINTEXT_LENGTH = 4;

	/** On-wire length of the encrypted per-frame header segment. */
	int FRAME_HEADER_LENGTH = FRAME_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH; // 20

	/** Stream-recognition tag prefixed to the first frame of a stream. */
	int TAG_LENGTH = 16;

	/** Width of the persistent stream identifier, in bytes (uint64). */
	int STREAM_ID_LENGTH = 8;

	/** Sliding replay/reorder window applied to the persistent stream id. */
	int REPLAY_WINDOW_SIZE = 256;

	// Stream header plaintext = [version:2][streamId:8]; chain key not carried.

	/** Plaintext length of the native stream header. */
	int STREAM_HEADER_PLAINTEXT_LENGTH = 2 + STREAM_ID_LENGTH; // 10

	/** On-wire length of the encrypted native stream header (nonce + ct + tag). */
	int STREAM_HEADER_LENGTH =
			NONCE_LENGTH + STREAM_HEADER_PLAINTEXT_LENGTH + MAC_LENGTH; // 50

	int SEGMENT_FRAME_HEADER = 0;
	int SEGMENT_MODE3FULL_HEADER = 1;
	int SEGMENT_BODY = 2;

	/** Streams this device originates (we allocate the stream id). */
	int DIRECTION_SEND = 0;
	/** Streams the peer originates (we validate the stream id against replay). */
	int DIRECTION_RECV = 1;
}
