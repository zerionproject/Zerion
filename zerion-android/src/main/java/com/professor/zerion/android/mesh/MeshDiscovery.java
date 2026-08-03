package com.professor.zerion.android.mesh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The rotating BLE discovery identity used by the offline mesh: a service UUID
 * derived from a shared secret and a 10-minute epoch, plus the session-nonce
 * tie-break that decides which of two peers dials out. Stateless.
 */
final class MeshDiscovery {

	static final long EPOCH_MS = 10 * 60 * 1000L;

	private static final byte[] MESH_DISCOVERY_SECRET = {
			(byte) 0x3a, (byte) 0x91, (byte) 0xe7, (byte) 0x42,
			(byte) 0xbc, (byte) 0x18, (byte) 0x6f, (byte) 0xd0,
			(byte) 0x59, (byte) 0xa3, (byte) 0x2e, (byte) 0x87,
			(byte) 0xf1, (byte) 0x4c, (byte) 0x9b, (byte) 0x60,
			(byte) 0xd7, (byte) 0x25, (byte) 0x8e, (byte) 0x33,
			(byte) 0xa9, (byte) 0x70, (byte) 0x1f, (byte) 0xc4,
			(byte) 0x6b, (byte) 0xe2, (byte) 0x5d, (byte) 0x08,
			(byte) 0x94, (byte) 0xbf, (byte) 0x71, (byte) 0x36};

	private MeshDiscovery() {
	}

	static long currentEpoch() {
		return System.currentTimeMillis() / EPOCH_MS;
	}

	static UUID discoveryUuid(long epoch, UUID fallback) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(MESH_DISCOVERY_SECRET);
			byte[] e = new byte[8];
			for (int i = 0; i < 8; i++) {
				e[i] = (byte) (epoch >>> (8 * (7 - i)));
			}
			byte[] h = md.digest(e);
			long msb = 0, lsb = 0;
			for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xFF);
			for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xFF);
			return new UUID(msb, lsb);
		} catch (NoSuchAlgorithmException ex) {
			return fallback;
		}
	}

	static int compareNonce(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int d = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (d != 0) return d;
		}
		return a.length - b.length;
	}
}
