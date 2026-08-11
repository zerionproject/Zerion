package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class KpId {

	public static final int SIZE = 16;

	private final byte[] bytes;
	private final int hash;

	public KpId(byte[] bytes) {
		if (bytes.length != SIZE) throw new IllegalArgumentException();
		this.bytes = bytes.clone();
		this.hash = Arrays.hashCode(this.bytes);
	}

	public static KpId of(byte[] encapsulationKey) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] full = md.digest(encapsulationKey);
			byte[] truncated = new byte[SIZE];
			System.arraycopy(full, 0, truncated, 0, SIZE);
			return new KpId(truncated);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 unavailable", e);
		}
	}

	public byte[] getBytes() {
		return bytes.clone();
	}

	public ByteBuffer asByteBuffer() {
		return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof KpId && Arrays.equals(bytes, ((KpId) o).bytes);
	}

	@Override
	public int hashCode() {
		return hash;
	}
}
