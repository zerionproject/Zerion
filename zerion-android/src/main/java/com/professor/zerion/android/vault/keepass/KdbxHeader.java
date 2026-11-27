package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class KdbxHeader {

	static final long SIGNATURE_1 = 0x9AA2D903L;
	static final long SIGNATURE_2 = 0xB54BFB67L;
	static final long SIGNATURE_2_KDB1 = 0xB54BFB65L;
	static final long SIGNATURE_2_PRERELEASE = 0xB54BFB66L;

	static final int MAX_HEADER_BYTES = 1024 * 1024;
	private static final int MAX_KDF_PARAMETERS = 64 * 1024;
	private static final int MAX_COMMENT = 64 * 1024;

	static final int ID_END = 0;
	static final int ID_COMMENT = 1;
	static final int ID_CIPHER = 2;
	static final int ID_COMPRESSION = 3;
	static final int ID_MASTER_SEED = 4;
	static final int ID_TRANSFORM_SEED = 5;
	static final int ID_TRANSFORM_ROUNDS = 6;
	static final int ID_ENCRYPTION_IV = 7;
	static final int ID_PROTECTED_STREAM_KEY = 8;
	static final int ID_STREAM_START_BYTES = 9;
	static final int ID_INNER_RANDOM_STREAM = 10;
	static final int ID_KDF_PARAMETERS = 11;
	static final int ID_PUBLIC_CUSTOM_DATA = 12;

	static final String CIPHER_AES = "31c1f2e6bf714350be5805216afc5aff";
	static final String CIPHER_CHACHA20 = "d6038a2b8b6f4cb5a524339a31dbb59a";
	static final String CIPHER_TWOFISH = "ad68f29f576f4bb9a36ad47af965346c";

	enum Cipher {
		AES_CBC,
		CHACHA20,
		TWOFISH_CBC
	}

	final int majorVersion;
	final int minorVersion;
	final int headerLength;
	final Cipher cipher;
	final boolean gzip;
	final byte[] masterSeed;
	final byte[] encryptionIv;
	@Nullable
	final byte[] transformSeed;
	final long transformRounds;
	@Nullable
	final byte[] protectedStreamKey;
	@Nullable
	final byte[] streamStartBytes;
	final int innerRandomStreamId;
	@Nullable
	final KdbxKdf.Params kdf;

	private KdbxHeader(int majorVersion, int minorVersion, int headerLength,
			Cipher cipher, boolean gzip, byte[] masterSeed,
			byte[] encryptionIv, @Nullable byte[] transformSeed,
			long transformRounds, @Nullable byte[] protectedStreamKey,
			@Nullable byte[] streamStartBytes, int innerRandomStreamId,
			@Nullable KdbxKdf.Params kdf) {
		this.majorVersion = majorVersion;
		this.minorVersion = minorVersion;
		this.headerLength = headerLength;
		this.cipher = cipher;
		this.gzip = gzip;
		this.masterSeed = masterSeed;
		this.encryptionIv = encryptionIv;
		this.transformSeed = transformSeed;
		this.transformRounds = transformRounds;
		this.protectedStreamKey = protectedStreamKey;
		this.streamStartBytes = streamStartBytes;
		this.innerRandomStreamId = innerRandomStreamId;
		this.kdf = kdf;
	}

	boolean isVersion4() {
		return majorVersion == 4;
	}

	byte[] rawBytes(byte[] file) {
		return Arrays.copyOf(file, headerLength);
	}

	static KdbxHeader parse(byte[] file) throws KdbxException {
		if (file.length < 12) throw KdbxException.unsupported("too short");
		long sig1 = KdbxBytes.u32(file, 0);
		long sig2 = KdbxBytes.u32(file, 4);
		if (sig1 != SIGNATURE_1) throw KdbxException.unsupported("signature");
		if (sig2 == SIGNATURE_2_KDB1 || sig2 == SIGNATURE_2_PRERELEASE) {
			throw KdbxException.unsupported("old KeePass format");
		}
		if (sig2 != SIGNATURE_2) throw KdbxException.unsupported("signature");
		int minor = KdbxBytes.u16(file, 8);
		int major = KdbxBytes.u16(file, 10);
		if (major != 3 && major != 4) {
			throw KdbxException.unsupported("version " + major + "." + minor);
		}
		boolean v4 = major == 4;
		int limit = Math.min(file.length, MAX_HEADER_BYTES);
		int pos = 12;
		boolean[] seen = new boolean[ID_PUBLIC_CUSTOM_DATA + 1];
		byte[] cipherId = null;
		Boolean gzip = null;
		byte[] masterSeed = null;
		byte[] transformSeed = null;
		long transformRounds = -1;
		byte[] encryptionIv = null;
		byte[] protectedStreamKey = null;
		byte[] streamStartBytes = null;
		int innerRandomStreamId = -1;
		KdbxKdf.Params kdf = null;
		boolean ended = false;
		while (!ended) {
			int fixed = v4 ? 5 : 3;
			if (pos + fixed > limit) throw KdbxException.corrupted("header end");
			int id = file[pos] & 0xFF;
			long size = v4 ? KdbxBytes.u32(file, pos + 1)
					: KdbxBytes.u16(file, pos + 1);
			pos += fixed;
			if (size > limit - pos) {
				throw KdbxException.corrupted("header field size");
			}
			int len = (int) size;
			if (id > ID_PUBLIC_CUSTOM_DATA) {
				throw KdbxException.unsupported("header field " + id);
			}
			if (seen[id]) {
				throw KdbxException.corrupted("duplicate header field " + id);
			}
			seen[id] = true;
			byte[] data = Arrays.copyOfRange(file, pos, pos + len);
			pos += len;
			switch (id) {
				case ID_END:
					ended = true;
					break;
				case ID_COMMENT:
					if (len > MAX_COMMENT) {
						throw KdbxException.corrupted("comment size");
					}
					break;
				case ID_CIPHER:
					requireLength(len, 16, "cipher id");
					cipherId = data;
					break;
				case ID_COMPRESSION: {
					requireLength(len, 4, "compression flags");
					long flags = KdbxBytes.u32(data, 0);
					if (flags == 0) gzip = false;
					else if (flags == 1) gzip = true;
					else throw KdbxException.unsupported("compression " + flags);
					break;
				}
				case ID_MASTER_SEED:
					requireLength(len, 32, "master seed");
					masterSeed = data;
					break;
				case ID_TRANSFORM_SEED:
					requireVersion3(v4, id);
					requireLength(len, 32, "transform seed");
					transformSeed = data;
					break;
				case ID_TRANSFORM_ROUNDS:
					requireVersion3(v4, id);
					requireLength(len, 8, "transform rounds");
					transformRounds = KdbxBytes.i64(data, 0);
					break;
				case ID_ENCRYPTION_IV:
					if (len != 16 && len != 12) {
						throw KdbxException.corrupted("iv size");
					}
					encryptionIv = data;
					break;
				case ID_PROTECTED_STREAM_KEY:
					requireVersion3(v4, id);
					if (len < 16 || len > 64) {
						throw KdbxException.corrupted("stream key size");
					}
					protectedStreamKey = data;
					break;
				case ID_STREAM_START_BYTES:
					requireVersion3(v4, id);
					requireLength(len, 32, "stream start bytes");
					streamStartBytes = data;
					break;
				case ID_INNER_RANDOM_STREAM:
					requireVersion3(v4, id);
					requireLength(len, 4, "inner stream id");
					innerRandomStreamId = (int) KdbxBytes.u32(data, 0);
					break;
				case ID_KDF_PARAMETERS:
					if (!v4) {
						throw KdbxException.unsupported("kdf parameters in version 3");
					}
					if (len > MAX_KDF_PARAMETERS) {
						throw KdbxException.corrupted("kdf size");
					}
					kdf = KdbxKdf.Params.fromDictionary(
							KdbxVariantDictionary.parse(data));
					break;
				case ID_PUBLIC_CUSTOM_DATA:
					if (!v4) {
						throw KdbxException.unsupported("custom data in version 3");
					}
					break;
				default:
					throw KdbxException.unsupported("header field " + id);
			}
		}
		if (cipherId == null || gzip == null || masterSeed == null
				|| encryptionIv == null) {
			throw KdbxException.corrupted("header incomplete");
		}
		Cipher cipher = cipherFor(cipherId);
		int ivLength = cipher == Cipher.CHACHA20 ? 12 : 16;
		if (encryptionIv.length != ivLength) {
			throw KdbxException.corrupted("iv length");
		}
		if (v4) {
			if (kdf == null) throw KdbxException.corrupted("kdf missing");
		} else {
			if (transformSeed == null || transformRounds < 0
					|| protectedStreamKey == null || streamStartBytes == null
					|| innerRandomStreamId < 0) {
				throw KdbxException.corrupted("header incomplete");
			}
			if (transformRounds == 0
					|| transformRounds > KdbxKdf.MAX_AES_ROUNDS) {
				throw KdbxException.tooLarge("transform rounds");
			}
			KdbxInnerStream.requireSupported(innerRandomStreamId);
		}
		return new KdbxHeader(major, minor, pos, cipher, gzip, masterSeed,
				encryptionIv, transformSeed, transformRounds,
				protectedStreamKey, streamStartBytes, innerRandomStreamId,
				kdf);
	}

	private static void requireVersion3(boolean v4, int id)
			throws KdbxException {
		if (v4) {
			throw KdbxException.unsupported(
					"version 3 field " + id + " in version 4");
		}
	}

	private static void requireLength(int len, int expected, String what)
			throws KdbxException {
		if (len != expected) throw KdbxException.corrupted(what + " size");
	}

	private static Cipher cipherFor(byte[] uuid) throws KdbxException {
		if (KdbxBytes.equalsUuid(uuid, CIPHER_AES)) return Cipher.AES_CBC;
		if (KdbxBytes.equalsUuid(uuid, CIPHER_CHACHA20)) return Cipher.CHACHA20;
		if (KdbxBytes.equalsUuid(uuid, CIPHER_TWOFISH)) return Cipher.TWOFISH_CBC;
		throw KdbxException.unsupported("cipher");
	}
}
