package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@NotNullByDefault
public class VaultItem {

	public enum ItemType {
		NOTE(1),
		IMAGE(2),
		VIDEO(3),
		DOCUMENT(4),
		AUDIO(5),
		PASSWORD(6),
		WALLET(7);

		private final int value;

		ItemType(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		public static ItemType fromValue(int value) {
			for (ItemType type : values()) {
				if (type.value == value) {
					return type;
				}
			}
			throw new IllegalArgumentException("Unknown item type: " + value);
		}
	}

	public final String id;
	public final ItemType type;
	public final String name;
	public final long createdTimestamp;
	public final long modifiedTimestamp;
	public final long size;
	public final byte[] thumbnailKey;
	public final boolean hasExtraPassword;
	public final byte[] extraPasswordSalt;

	public static final int DEFAULT_EXTRA_MEMORY_KB = 256 * 1024;
	public static final int DEFAULT_EXTRA_ITERATIONS = 3;
	public static final int DEFAULT_EXTRA_PARALLELISM = 1;

	public final int extraPasswordMemoryKb;
	public final int extraPasswordIterations;
	public final int extraPasswordParallelism;

	public final byte[] encryptedKey;
	public final byte[] nonce;
	public final int version;

	public VaultItem(String id, ItemType type, String name, long createdTimestamp,
			long modifiedTimestamp, long size, byte[] thumbnailKey,
			boolean hasExtraPassword, byte[] extraPasswordSalt,
			int extraPasswordMemoryKb, int extraPasswordIterations,
			int extraPasswordParallelism,
			byte[] encryptedKey, byte[] nonce, int version) {
		this.id = id;
		this.type = type;
		this.name = name;
		this.createdTimestamp = createdTimestamp;
		this.modifiedTimestamp = modifiedTimestamp;
		this.size = size;
		this.thumbnailKey = thumbnailKey;
		this.hasExtraPassword = hasExtraPassword;
		this.extraPasswordSalt = extraPasswordSalt;
		this.extraPasswordMemoryKb = extraPasswordMemoryKb;
		this.extraPasswordIterations = extraPasswordIterations;
		this.extraPasswordParallelism = extraPasswordParallelism;
		this.encryptedKey = encryptedKey;
		this.nonce = nonce;
		this.version = version;
	}

	public static VaultItem createNew(ItemType type, String name, long size,
			byte[] encryptedKey, byte[] nonce) {
		String id = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		return new VaultItem(
				id, type, name, now, now, size,
				new byte[0],
				false,
				new byte[0],
				DEFAULT_EXTRA_MEMORY_KB, DEFAULT_EXTRA_ITERATIONS,
				DEFAULT_EXTRA_PARALLELISM,
				encryptedKey, nonce, 1
		);
	}

	public static VaultItem createNewWithPassword(ItemType type, String name, long size,
			byte[] encryptedKey, byte[] nonce, byte[] extraPasswordSalt,
			int extraPasswordMemoryKb, int extraPasswordIterations,
			int extraPasswordParallelism) {
		String id = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		return new VaultItem(
				id, type, name, now, now, size,
				new byte[0],
				true,
				extraPasswordSalt,
				extraPasswordMemoryKb, extraPasswordIterations,
				extraPasswordParallelism,
				encryptedKey, nonce, 2
		);
	}

	public byte[] serializeMetadata() {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);

		int totalSize = 36 +
				4 +
				4 + nameBytes.length +
				8 +
				8 +
				8 +
				4 + thumbnailKey.length +
				1 +
				4 + extraPasswordSalt.length +
				4 + encryptedKey.length +
				4 + nonce.length +
				4 +
				(version >= 2 ? 12 : 0);

		ByteBuffer buffer = ByteBuffer.allocate(totalSize);

		byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
		buffer.put(idBytes);

		buffer.putInt(type.getValue());

		buffer.putInt(nameBytes.length);
		buffer.put(nameBytes);

		buffer.putLong(createdTimestamp);
		buffer.putLong(modifiedTimestamp);

		buffer.putLong(size);

		buffer.putInt(thumbnailKey.length);
		buffer.put(thumbnailKey);

		buffer.put((byte) (hasExtraPassword ? 1 : 0));
		buffer.putInt(extraPasswordSalt.length);
		buffer.put(extraPasswordSalt);

		buffer.putInt(encryptedKey.length);
		buffer.put(encryptedKey);
		buffer.putInt(nonce.length);
		buffer.put(nonce);
		buffer.putInt(version);
		if (version >= 2) {
			buffer.putInt(extraPasswordMemoryKb);
			buffer.putInt(extraPasswordIterations);
			buffer.putInt(extraPasswordParallelism);
		}

		return buffer.array();
	}

	public static VaultItem deserializeMetadata(byte[] data) {
		ByteBuffer buffer = ByteBuffer.wrap(data);

		byte[] idBytes = new byte[36];
		buffer.get(idBytes);
		String id = new String(idBytes, StandardCharsets.US_ASCII);

		ItemType type = ItemType.fromValue(buffer.getInt());

		int nameLength = readBoundedLength(buffer, 4096);
		byte[] nameBytes = new byte[nameLength];
		buffer.get(nameBytes);
		String name = new String(nameBytes, StandardCharsets.UTF_8);

		long createdTimestamp = buffer.getLong();
		long modifiedTimestamp = buffer.getLong();

		long size = buffer.getLong();

		int thumbKeyLength = readBoundedLength(buffer, 4096);
		byte[] thumbnailKey = new byte[thumbKeyLength];
		buffer.get(thumbnailKey);

		boolean hasExtraPassword = buffer.get() == 1;
		int saltLength = readBoundedLength(buffer, 256);
		byte[] extraPasswordSalt = new byte[saltLength];
		buffer.get(extraPasswordSalt);

		int keyLength = readBoundedLength(buffer, 1024);
		byte[] encryptedKey = new byte[keyLength];
		buffer.get(encryptedKey);

		int nonceLength = readBoundedLength(buffer, 64);
		byte[] nonce = new byte[nonceLength];
		buffer.get(nonce);

		int version = buffer.getInt();

		int extraMemoryKb = DEFAULT_EXTRA_MEMORY_KB;
		int extraIterations = DEFAULT_EXTRA_ITERATIONS;
		int extraParallelism = DEFAULT_EXTRA_PARALLELISM;
		if (version >= 2 && buffer.remaining() >= 12) {
			extraMemoryKb = buffer.getInt();
			extraIterations = buffer.getInt();
			extraParallelism = buffer.getInt();
		}

		return new VaultItem(
				id, type, name, createdTimestamp, modifiedTimestamp, size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				extraMemoryKb, extraIterations, extraParallelism,
				encryptedKey, nonce, version
		);
	}

	private static int readBoundedLength(ByteBuffer buffer, int max) {
		int len = buffer.getInt();
		if (len < 0 || len > max || len > buffer.remaining()) {
			throw new IllegalArgumentException(
					"Vault metadata length out of bounds: " + len);
		}
		return len;
	}

	public VaultItem updateModified() {
		return new VaultItem(
				id, type, name, createdTimestamp, System.currentTimeMillis(), size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				extraPasswordMemoryKb, extraPasswordIterations,
				extraPasswordParallelism,
				encryptedKey, nonce, version
		);
	}

	public VaultItem updateName(String newName) {
		return new VaultItem(
				id, type, newName, createdTimestamp, System.currentTimeMillis(), size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				extraPasswordMemoryKb, extraPasswordIterations,
				extraPasswordParallelism,
				encryptedKey, nonce, version
		);
	}
}