package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PqChunk {

	private final byte type;
	private final int index;
	private final byte[] data;

	public PqChunk(byte type, int index, byte[] data) {
		this.type = type;
		this.index = index;
		this.data = data;
	}

	public byte getType() {
		return type;
	}

	public int getIndex() {
		return index;
	}

	public byte[] getData() {
		return data;
	}

	public int getLength() {
		return data.length;
	}

	public boolean isEkSeed() {
		return type == PcsConstants.PQ_CHUNK_TYPE_EK_SEED;
	}

	public boolean isEkVector() {
		return type == PcsConstants.PQ_CHUNK_TYPE_EK_VEC;
	}

	public boolean isCiphertext() {
		return type == PcsConstants.PQ_CHUNK_TYPE_CT;
	}
}
