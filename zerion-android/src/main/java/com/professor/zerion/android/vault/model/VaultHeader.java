package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@NotNullByDefault
public class VaultHeader {

	public static final int CURRENT_VERSION = 2;
	public static final int VERSION_KEYSTORE_FACTOR = 2;
	private static final byte[] MAGIC_BYTES = "ZVLT".getBytes(StandardCharsets.US_ASCII);

	public static final int FEATURE_FLAG_KDF_ARGON2ID = 0x00000001;

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
	public final int keySlot;
	public final byte[] wrappedMasterKey;

	public VaultHeader(int version, byte[] salt, int kdfMemoryKb, int kdfIterations,
			int kdfParallelism, byte[] wrappedKeystoreBlob, byte[] biometricTokenSalt,
			byte[] passwordVerificationMac, long createdTimestamp, long modifiedTimestamp,
			int featureFlags) {
		this(version, salt, kdfMemoryKb, kdfIterations, kdfParallelism,
				wrappedKeystoreBlob, biometricTokenSalt,
				passwordVerificationMac, createdTimestamp, modifiedTimestamp,
				featureFlags, 0, new byte[0]);
	}

	public VaultHeader(int version, byte[] salt, int kdfMemoryKb,
			int kdfIterations, int kdfParallelism, byte[] wrappedKeystoreBlob,
			byte[] biometricTokenSalt, byte[] passwordVerificationMac,
			long createdTimestamp, long modifiedTimestamp, int featureFlags,
			int keySlot, byte[] wrappedMasterKey) {
		this.keySlot = keySlot;
		this.wrappedMasterKey = wrappedMasterKey;
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

	public static VaultHeader createKeystoreFactor(byte[] salt, int kdfMemoryKb,
			int kdfIterations, byte[] wrappedKeystoreBlob,
			byte[] biometricTokenSalt, byte[] passwordVerificationMac,
			long createdTimestamp, int keySlot, byte[] wrappedMasterKey) {
		return new VaultHeader(VERSION_KEYSTORE_FACTOR, salt, kdfMemoryKb,
				kdfIterations, 1, wrappedKeystoreBlob, biometricTokenSalt,
				passwordVerificationMac, createdTimestamp,
				System.currentTimeMillis(), FEATURE_FLAG_KDF_ARGON2ID, keySlot,
				wrappedMasterKey);
	}

	public static VaultHeader createNew(byte[] salt, int kdfMemoryKb, int kdfIterations,
			byte[] wrappedKeystoreBlob, byte[] biometricTokenSalt, byte[] passwordVerificationMac) {
		long now = System.currentTimeMillis();
		return new VaultHeader(
				1,
				salt,
				kdfMemoryKb,
				kdfIterations,
				1,
				wrappedKeystoreBlob,
				biometricTokenSalt,
				passwordVerificationMac,
				now,
				now,
				FEATURE_FLAG_KDF_ARGON2ID
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
		if (version >= VERSION_KEYSTORE_FACTOR) {
			totalSize += 4 + 4 + wrappedMasterKey.length;
		}

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
		if (version >= VERSION_KEYSTORE_FACTOR) {
			buffer.putInt(keySlot);
			buffer.putInt(wrappedMasterKey.length);
			buffer.put(wrappedMasterKey);
		}

		return buffer.array();
	}

	static final int MAX_FIELD_LENGTH = 128;
	static final int MAX_BLOB_LENGTH = 4096;

	private static int boundedLength(int length, int max) {
		if (length < 0 || length > max) {
			throw new IllegalArgumentException("Vault header field too long");
		}
		return length;
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
		byte[] salt = new byte[boundedLength(saltLength, MAX_FIELD_LENGTH)];
		buffer.get(salt);

		int kdfMemoryKb = buffer.getInt();
		int kdfIterations = buffer.getInt();
		int kdfParallelism = buffer.getInt();
		com.professor.zerion.android.vault.crypto.Argon2.requireSaneParams(
				kdfMemoryKb, kdfIterations, kdfParallelism);

		int blobLength = buffer.getInt();
		byte[] wrappedKeystoreBlob =
				new byte[boundedLength(blobLength, MAX_BLOB_LENGTH)];
		buffer.get(wrappedKeystoreBlob);

		int bioSaltLength = buffer.getInt();
		byte[] biometricTokenSalt =
				new byte[boundedLength(bioSaltLength, MAX_FIELD_LENGTH)];
		buffer.get(biometricTokenSalt);

		byte[] passwordVerificationMac = new byte[0];
		if (buffer.remaining() >= 4) {
			int macLength = buffer.getInt();
			if (macLength > MAX_FIELD_LENGTH) {
				throw new IllegalArgumentException("Vault header field too long");
			}
			if (macLength > 0 && buffer.remaining() >= macLength) {
				passwordVerificationMac = new byte[macLength];
				buffer.get(passwordVerificationMac);
			}
		}

		long createdTimestamp = buffer.remaining() >= 8 ? buffer.getLong() : System.currentTimeMillis();
		long modifiedTimestamp = buffer.remaining() >= 8 ? buffer.getLong() : System.currentTimeMillis();
		int featureFlags = buffer.remaining() >= 4 ? buffer.getInt() : 0;

		if (version >= 1 &&
				(featureFlags & FEATURE_FLAG_KDF_ARGON2ID) == 0) {
			throw new IllegalArgumentException(
					"Vault header missing required Argon2id flag");
		}

		int keySlot = 0;
		byte[] wrappedMasterKey = new byte[0];
		if (version >= VERSION_KEYSTORE_FACTOR) {
			keySlot = buffer.getInt();
			if (keySlot != 1 && keySlot != 2) {
				throw new IllegalArgumentException("Invalid vault key slot");
			}
			wrappedMasterKey = new byte[boundedLength(buffer.getInt(),
					MAX_FIELD_LENGTH)];
			buffer.get(wrappedMasterKey);
			if (passwordVerificationMac.length == 0
					|| wrappedMasterKey.length == 0) {
				throw new IllegalArgumentException(
						"Vault header incomplete");
			}
		}

		return new VaultHeader(
				version, salt, kdfMemoryKb, kdfIterations, kdfParallelism,
				wrappedKeystoreBlob, biometricTokenSalt, passwordVerificationMac,
				createdTimestamp, modifiedTimestamp, featureFlags, keySlot,
				wrappedMasterKey
		);
	}

	public VaultHeader updateModified() {
		return new VaultHeader(
				version, salt, kdfMemoryKb, kdfIterations, kdfParallelism,
				wrappedKeystoreBlob, biometricTokenSalt, passwordVerificationMac,
				createdTimestamp, System.currentTimeMillis(), featureFlags,
				keySlot, wrappedMasterKey
		);
	}
}