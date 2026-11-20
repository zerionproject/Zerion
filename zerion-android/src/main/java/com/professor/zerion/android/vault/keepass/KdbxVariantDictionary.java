package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
final class KdbxVariantDictionary {

	static final int TYPE_UINT32 = 0x04;
	static final int TYPE_UINT64 = 0x05;
	static final int TYPE_BOOL = 0x08;
	static final int TYPE_INT32 = 0x0C;
	static final int TYPE_INT64 = 0x0D;
	static final int TYPE_STRING = 0x18;
	static final int TYPE_BYTES = 0x42;

	private static final int MAX_ENTRIES = 64;
	private static final int MAX_NAME = 1024;
	private static final int MAX_VALUE = 64 * 1024;

	static final class Item {
		final int type;
		final byte[] value;

		Item(int type, byte[] value) {
			this.type = type;
			this.value = value;
		}
	}

	private final Map<String, Item> items = new LinkedHashMap<>();

	static KdbxVariantDictionary parse(byte[] data) throws KdbxException {
		if (data.length < 3) throw KdbxException.corrupted("kdf dictionary");
		int version = KdbxBytes.u16(data, 0);
		if ((version & 0xFF00) != 0x0100) {
			throw KdbxException.unsupported("kdf dictionary version");
		}
		KdbxVariantDictionary dict = new KdbxVariantDictionary();
		int pos = 2;
		while (true) {
			if (pos >= data.length) {
				throw KdbxException.corrupted("kdf dictionary end");
			}
			int type = data[pos++] & 0xFF;
			if (type == 0) break;
			if (dict.items.size() >= MAX_ENTRIES) {
				throw KdbxException.corrupted("kdf dictionary size");
			}
			if (pos + 4 > data.length) {
				throw KdbxException.corrupted("kdf name size");
			}
			int nameSize = KdbxBytes.i32(data, pos);
			pos += 4;
			if (nameSize < 0 || nameSize > MAX_NAME
					|| pos + nameSize > data.length) {
				throw KdbxException.corrupted("kdf name");
			}
			String name = new String(data, pos, nameSize,
					StandardCharsets.UTF_8);
			pos += nameSize;
			if (pos + 4 > data.length) {
				throw KdbxException.corrupted("kdf value size");
			}
			int valueSize = KdbxBytes.i32(data, pos);
			pos += 4;
			if (valueSize < 0 || valueSize > MAX_VALUE
					|| pos + valueSize > data.length) {
				throw KdbxException.corrupted("kdf value");
			}
			checkSize(type, valueSize);
			byte[] value = new byte[valueSize];
			System.arraycopy(data, pos, value, 0, valueSize);
			pos += valueSize;
			if (dict.items.put(name, new Item(type, value)) != null) {
				throw KdbxException.corrupted("kdf duplicate name");
			}
		}
		if (pos != data.length) {
			throw KdbxException.corrupted("kdf trailing data");
		}
		return dict;
	}

	private static void checkSize(int type, int size) throws KdbxException {
		int expected;
		switch (type) {
			case TYPE_UINT32:
			case TYPE_INT32:
				expected = 4;
				break;
			case TYPE_UINT64:
			case TYPE_INT64:
				expected = 8;
				break;
			case TYPE_BOOL:
				expected = 1;
				break;
			case TYPE_STRING:
			case TYPE_BYTES:
				return;
			default:
				throw KdbxException.unsupported("kdf value type " + type);
		}
		if (size != expected) throw KdbxException.corrupted("kdf value size");
	}

	@Nullable
	byte[] bytes(String name) {
		Item item = items.get(name);
		if (item == null || item.type != TYPE_BYTES) return null;
		return item.value;
	}

	byte[] requireBytes(String name) throws KdbxException {
		byte[] value = bytes(name);
		if (value == null) throw KdbxException.corrupted("kdf missing " + name);
		return value;
	}

	long requireUint32(String name) throws KdbxException {
		Item item = items.get(name);
		if (item == null || item.type != TYPE_UINT32) {
			throw KdbxException.corrupted("kdf missing " + name);
		}
		return KdbxBytes.u32(item.value, 0);
	}

	long requireUint64(String name) throws KdbxException {
		Item item = items.get(name);
		if (item == null || item.type != TYPE_UINT64) {
			throw KdbxException.corrupted("kdf missing " + name);
		}
		return KdbxBytes.i64(item.value, 0);
	}
}
