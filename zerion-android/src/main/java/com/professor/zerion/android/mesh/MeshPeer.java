package com.professor.zerion.android.mesh;

import javax.annotation.concurrent.Immutable;

@Immutable
final class MeshPeer {

	final int contact;
	final byte[] advertKey;
	final byte[] linkKey;
	final boolean keepsOwnDial;

	MeshPeer(int contact, byte[] advertKey, byte[] linkKey,
			boolean keepsOwnDial) {
		this.contact = contact;
		this.advertKey = advertKey;
		this.linkKey = linkKey;
		this.keepsOwnDial = keepsOwnDial;
	}
}
