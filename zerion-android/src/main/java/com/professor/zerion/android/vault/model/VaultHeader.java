package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@NotNullByDefault
public class VaultHeader {

	public static final int CURRENT_VERSION = 1;
	private static final byte[] MAGIC_BYTES = "ZVLT".getBytes(StandardCharsets.US_ASCII);

	public final int version;
	public final byte[] salt;
	public final int kdfMemoryKb;
	public final int kdfIterations;
	public final int kdfParallelism;
	public final byte[] wrappedKeystoreBlob;
	public final byte[] biometricTokenSalt;
	public final byte[] passwordVerificationMac;
	public final long createdTimestamp;
	public final long modifiedTimestamp;
	public final int featureFlags;

	public VaultHeader(int version, byte[] salt, int kdfMemoryKb, int kdfIterations,
			int kdfParallelism, byte[] wrappedKeystoreBlob, byte[] biometricTokenSalt,
			byte[] passwordVerificationMac, long createdTimestamp, long modifiedTimestamp,
			int featureFlags) {
		this.version = version;
		this.salt = salt;
		this.kdfMemoryKb = kdfMemoryKb;
		this.kdfIterations = kdfIterations;
		this.kdfParallelism = kdfParallelism;
		this.wrappedKeystoreBlob = wrappedKeystoreBlob;
		this.biometricTokenSalt = biometricTokenSalt;
		this.passwordVerificationMac = passwordVerificationMac;
		this.createdTimestamp = createdTimestamp;
		this.modifiedTimestamp = modifiedTimestamp;
		this.featureFlags = featureFlags;
	}

	public static VaultHeader createNew(byte[] salt, int kdfMemoryKb, int kdfIterations,
			byte[] wrappedKeystoreBlob, byte[] biometricTokenSalt, byte[] passwordVerificationMac) {
		long now = System.currentTimeMillis();
		return new VaultHeader(
				CURRENT_VERSION,
				salt,
				kdfMemoryKb,
				kdfIterations,
				1,
				wrappedKeystoreBlob,
				biometricTokenSalt,
				passwordVerificationMac,
				now,
				now,
				0
		);
	}

	public byte[] toBytes() {
		int totalSize = 4 +
				4 +
				4 + salt.length +
				4 +
				4 +
				4 +
				4 + wrappedKeystoreBlob.length +
				4 + biometricTokenSalt.length +
				4 + passwordVerificationMac.length +
				8 +
				8 +
				4;

		ByteBuffer buffer = ByteBuffer.allocate(totalSize);

		buffer.put(MAGIC_BYTES);
		buffer.putInt(version);
		buffer.putInt(salt.length);
		buffer.put(salt);
		buffer.putInt(kdfMemoryKb);
		buffer.putInt(kdfIterations);
		buffer.putInt(kdfParallelism);
		buffer.putInt(wrappedKeystoreBlob.length);
		buffer.put(wrappedKeystoreBlob);
		buffer.putInt(biometricTokenSalt.length);
		buffer.put(biometricTokenSalt);
		buffer.putInt(passwordVerificationMac.length);
		buffer.put(passwordVerificationMac);
		buffer.putLong(createdTimestamp);
		buffer.putLong(modifiedTimestamp);
		buffer.putInt(featureFlags);

		return buffer.array();
	}

	public static VaultHeader fromBytes(byte[] data) {
		ByteBuffer buffer = ByteBuffer.wrap(data);

		byte[] magic = new byte[4];
		buffer.get(magic);
		if (!Arrays.equals(magic, MAGIC_BYTES)) {
			throw new IllegalArgumentException("Invalid vault header magic");
		}

		int version = buffer.getInt();
		if (version > CURRENT_VERSION) {
			throw new IllegalArgumentException("Unsupported vault version: " + version);
		}

		int saltLength = buffer.getInt();
		byte[] salt = new byte[saltLength];
		buffer.get(salt);

		int kdfMemoryKb = buffer.getInt();
		int kdfIterations = buffer.getInt();
		int kdfParallelism = buffer.getInt();

		int blobLength = buffer.getInt();
		byte[] wrappedKeystoreBlob = new byte[blobLength];
		buffer.get(wrappedKeystoreBlob);

		int bioSaltLength = buffer.getInt();
		byte[] biometricTokenSalt = new byte[bioSaltLength];
		buffer.get(biometricTokenSalt);

		byte[] passwordVerificationMac = new byte[0];
		if (buffer.remaining() >= 4) {
			int macLength = buffer.getInt();
			if (macLength > 0 && buffer.remaining() >= macLength) {
				passwordVerificationMac = new byte[macLength];
				buffer.get(passwordVerificationMac);
			}
		}

		long createdTimestamp = buffer.remaining() >= 8 ? buffer.getLong() : System.currentTimeMillis();
		long modifiedTimestamp = buffer.remaining() >= 8 ? buffer.getLong() : System.currentTimeMillis();
		int featureFlags = buffer.remaining() >= 4 ? buffer.getInt() : 0;

		return new VaultHeader(
				version, salt, kdfMemoryKb, kdfIterations, kdfParallelism,
				wrappedKeystoreBlob, biometricTokenSalt, passwordVerificationMac,
				createdTimestamp, modifiedTimestamp, featureFlags
		);
	}

	public VaultHeader updateModified() {
		return new VaultHeader(
				version, salt, kdfMemoryKb, kdfIterations, kdfParallelism,
				wrappedKeystoreBlob, biometricTokenSalt, passwordVerificationMac,
				createdTimestamp, System.currentTimeMillis(), featureFlags
		);
	}
}