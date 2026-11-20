package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
final class KdbxKdf {

	static final String KDF_AES = "c9d9f39a628a4460bf740d08c18a4fea";
	static final String KDF_ARGON2D = "ef636ddf8c29444b91f7a9a403e30a0c";
	static final String KDF_ARGON2ID = "9e298b1956db4773b23dfc3ec6f0a1e6";

	static final long MAX_AES_ROUNDS = 100_000_000L;
	static final long MIN_ARGON2_MEMORY = 8 * 1024L;
	static final long MAX_ARGON2_MEMORY = 1024L * 1024 * 1024;
	static final long MAX_ARGON2_ITERATIONS = 1000;
	static final long MAX_ARGON2_PARALLELISM = 32;
	static final int MIN_ARGON2_SALT = 8;
	static final int MAX_ARGON2_SALT = 128;
	static final int KEY_LENGTH = 32;

	enum Type {
		AES,
		ARGON2D,
		ARGON2ID
	}

	static final class Params {
		final Type type;
		final byte[] salt;
		final long rounds;
		final long memoryBytes;
		final long iterations;
		final int parallelism;
		final int version;

		private Params(Type type, byte[] salt, long rounds, long memoryBytes,
				long iterations, int parallelism, int version) {
			this.type = type;
			this.salt = salt;
			this.rounds = rounds;
			this.memoryBytes = memoryBytes;
			this.iterations = iterations;
			this.parallelism = parallelism;
			this.version = version;
		}

		static Params aes(byte[] seed, long rounds) throws KdbxException {
			if (seed.length != 32) throw KdbxException.corrupted("kdf seed");
			if (rounds <= 0 || rounds > MAX_AES_ROUNDS) {
				throw KdbxException.tooLarge("kdf rounds");
			}
			return new Params(Type.AES, seed, rounds, 0, 0, 0, 0);
		}

		static Params fromDictionary(KdbxVariantDictionary dict)
				throws KdbxException {
			byte[] uuid = dict.requireBytes("$UUID");
			if (KdbxBytes.equalsUuid(uuid, KDF_AES)) {
				return aes(dict.requireBytes("S"), dict.requireUint64("R"));
			}
			Type type;
			if (KdbxBytes.equalsUuid(uuid, KDF_ARGON2D)) type = Type.ARGON2D;
			else if (KdbxBytes.equalsUuid(uuid, KDF_ARGON2ID)) type = Type.ARGON2ID;
			else throw KdbxException.unsupported("kdf");
			byte[] salt = dict.requireBytes("S");
			if (salt.length < MIN_ARGON2_SALT || salt.length > MAX_ARGON2_SALT) {
				throw KdbxException.corrupted("kdf salt");
			}
			long parallelism = dict.requireUint32("P");
			long memory = dict.requireUint64("M");
			long iterations = dict.requireUint64("I");
			long version = dict.requireUint32("V");
			if (version != 0x13 && version != 0x10) {
				throw KdbxException.unsupported("argon2 version");
			}
			if (memory < 0 || memory > MAX_ARGON2_MEMORY
					|| iterations < 0 || iterations > MAX_ARGON2_ITERATIONS
					|| parallelism > MAX_ARGON2_PARALLELISM) {
				throw KdbxException.tooLarge("argon2 parameters");
			}
			if (memory < MIN_ARGON2_MEMORY || iterations < 1
					|| parallelism < 1
					|| memory / 1024 < 8L * parallelism) {
				throw KdbxException.corrupted("argon2 parameters");
			}
			return new Params(type, salt, 0, memory, iterations,
					(int) parallelism, (int) version);
		}
	}

	private KdbxKdf() {
	}

	static byte[] compositeKey(@Nullable char[] password,
			@Nullable byte[] keyFile) throws KdbxException {
		boolean hasPassword = password != null && password.length > 0;
		boolean hasKeyFile = keyFile != null && keyFile.length > 0;
		if (!hasPassword && !hasKeyFile) {
			throw KdbxException.wrongKey("no credentials");
		}
		byte[] passwordPart = new byte[0];
		byte[] keyFilePart = new byte[0];
		byte[] passwordBytes = null;
		try {
			if (hasPassword) {
				passwordBytes = utf8(password);
				passwordPart = KdbxBytes.sha256(passwordBytes);
			}
			if (hasKeyFile) {
				keyFilePart = KdbxKeyFile.keyFromFile(keyFile);
			}
			return KdbxBytes.sha256(passwordPart, keyFilePart);
		} finally {
			if (passwordBytes != null) KdbxBytes.wipe(passwordBytes);
			KdbxBytes.wipe(passwordPart);
			KdbxBytes.wipe(keyFilePart);
		}
	}

	private static byte[] utf8(char[] chars) {
		ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] out = new byte[buffer.remaining()];
		buffer.get(out);
		if (buffer.hasArray()) KdbxBytes.wipe(buffer.array());
		return out;
	}

	static byte[] transform(byte[] compositeKey, Params params)
			throws KdbxException {
		switch (params.type) {
			case AES:
				return transformAes(compositeKey, params.salt, params.rounds);
			case ARGON2D:
			case ARGON2ID:
				return transformArgon2(compositeKey, params);
			default:
				throw KdbxException.unsupported("kdf");
		}
	}

	static byte[] transformAes(byte[] compositeKey, byte[] seed, long rounds)
			throws KdbxException {
		if (compositeKey.length != KEY_LENGTH) {
			throw KdbxException.corrupted("composite key");
		}
		byte[] block = compositeKey.clone();
		try {
			Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
			aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(seed, "AES"));
			for (long i = 0; i < rounds; i++) {
				int n = aes.update(block, 0, block.length, block, 0);
				if (n != block.length) {
					throw KdbxException.corrupted("aes kdf");
				}
			}
			return KdbxBytes.sha256(block);
		} catch (GeneralSecurityException e) {
			throw KdbxException.corrupted("aes kdf");
		} finally {
			KdbxBytes.wipe(block);
		}
	}

	private static byte[] transformArgon2(byte[] compositeKey, Params params)
			throws KdbxException {
		int bcType = params.type == Type.ARGON2ID
				? Argon2Parameters.ARGON2_id : Argon2Parameters.ARGON2_d;
		int bcVersion = params.version == 0x10
				? Argon2Parameters.ARGON2_VERSION_10
				: Argon2Parameters.ARGON2_VERSION_13;
		Argon2Parameters bcParams = new Argon2Parameters.Builder(bcType)
				.withVersion(bcVersion)
				.withMemoryAsKB((int) (params.memoryBytes / 1024))
				.withIterations((int) params.iterations)
				.withParallelism(params.parallelism)
				.withSalt(params.salt)
				.build();
		byte[] out = new byte[KEY_LENGTH];
		try {
			Argon2BytesGenerator generator = new Argon2BytesGenerator();
			generator.init(bcParams);
			generator.generateBytes(compositeKey, out);
			return out;
		} catch (OutOfMemoryError e) {
			Arrays.fill(out, (byte) 0);
			throw KdbxException.tooLarge("argon2 memory");
		} catch (RuntimeException e) {
			Arrays.fill(out, (byte) 0);
			throw KdbxException.corrupted("argon2");
		}
	}
}
