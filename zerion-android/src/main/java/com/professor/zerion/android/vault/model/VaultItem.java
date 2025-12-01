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
		PASSWORD(6);

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

	public final byte[] encryptedKey;
	public final byte[] nonce;
	public final int version;

	public VaultItem(String id, ItemType type, String name, long createdTimestamp,
			long modifiedTimestamp, long size, byte[] thumbnailKey,
			boolean hasExtraPassword, byte[] extraPasswordSalt,
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
				encryptedKey, nonce, 1
		);
	}

	public static VaultItem createNewWithPassword(ItemType type, String name, long size,
			byte[] encryptedKey, byte[] nonce, byte[] extraPasswordSalt) {
		String id = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		return new VaultItem(
				id, type, name, now, now, size,
				new byte[0],
				true,
				extraPasswordSalt,
				encryptedKey, nonce, 1
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
				4;

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

		return buffer.array();
	}

	public static VaultItem deserializeMetadata(byte[] data) {
		ByteBuffer buffer = ByteBuffer.wrap(data);

		byte[] idBytes = new byte[36];
		buffer.get(idBytes);
		String id = new String(idBytes, StandardCharsets.US_ASCII);

		ItemType type = ItemType.fromValue(buffer.getInt());

		int nameLength = buffer.getInt();
		byte[] nameBytes = new byte[nameLength];
		buffer.get(nameBytes);
		String name = new String(nameBytes, StandardCharsets.UTF_8);

		long createdTimestamp = buffer.getLong();
		long modifiedTimestamp = buffer.getLong();

		long size = buffer.getLong();

		int thumbKeyLength = buffer.getInt();
		byte[] thumbnailKey = new byte[thumbKeyLength];
		buffer.get(thumbnailKey);

		boolean hasExtraPassword = buffer.get() == 1;
		int saltLength = buffer.getInt();
		byte[] extraPasswordSalt = new byte[saltLength];
		buffer.get(extraPasswordSalt);

		int keyLength = buffer.getInt();
		byte[] encryptedKey = new byte[keyLength];
		buffer.get(encryptedKey);

		int nonceLength = buffer.getInt();
		byte[] nonce = new byte[nonceLength];
		buffer.get(nonce);

		int version = buffer.getInt();

		return new VaultItem(
				id, type, name, createdTimestamp, modifiedTimestamp, size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				encryptedKey, nonce, version
		);
	}

	public VaultItem updateModified() {
		return new VaultItem(
				id, type, name, createdTimestamp, System.currentTimeMillis(), size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				encryptedKey, nonce, version
		);
	}

	public VaultItem updateName(String newName) {
		return new VaultItem(
				id, type, newName, createdTimestamp, System.currentTimeMillis(), size,
				thumbnailKey, hasExtraPassword, extraPasswordSalt,
				encryptedKey, nonce, version
		);
	}
}