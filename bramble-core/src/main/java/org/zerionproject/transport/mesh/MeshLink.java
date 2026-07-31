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
}
