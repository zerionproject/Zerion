package org.zerionproject.transport.mesh;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MeshLink {

	String getId();

	void broadcast(byte[] frame);

	default void broadcast(byte[] frame, @javax.annotation.Nullable
			String exceptPeerId) {
		broadcast(frame);
	}
}
