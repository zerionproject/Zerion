package org.zerionproject.transport.mesh;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * One radio neighbourhood the forwarder can broadcast into: a Bluetooth LE
 * connection set or a Wi-Fi Direct group. The radio implementations live in the
 * Android layer and are the device-tested part; the forwarder above talks only
 * to this seam, so its flooding logic is unit-testable without any radio.
 */
@NotNullByDefault
public interface MeshLink {

	/** A stable id for this link, so the forwarder can avoid echoing a frame
	 * back to the link it arrived on. */
	String getId();

	/** Broadcasts an encoded {@link MeshFrame} to every neighbour on this link.
	 * Best-effort; delivery is not guaranteed. */
	void broadcast(byte[] frame);

	/** Broadcasts to every neighbour on this link except {@code exceptPeerId}
	 * (the neighbour a relayed frame arrived from), so a relay does not echo a
	 * frame straight back to its sender. A null id excludes nobody. */
	default void broadcast(byte[] frame, @javax.annotation.Nullable
			String exceptPeerId) {
		broadcast(frame);
	}
}
