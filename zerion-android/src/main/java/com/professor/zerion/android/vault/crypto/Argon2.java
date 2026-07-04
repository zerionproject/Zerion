package com.professor.zerion.android.vault.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

@NotNullByDefault
public class Argon2 {

	public static final int DEFAULT_MEMORY_KB = 256 * 1024;
	public static final int DEFAULT_ITERATIONS = 3;
	public static final int DEFAULT_PARALLELISM = 1;
	public static final int DEFAULT_SALT_LENGTH = 32;
	public static final int DEFAULT_HASH_LENGTH = 32;

	public static final int LOW_MEMORY_KB = 128 * 1024;
	public static final int LOW_ITERATIONS = 2;
	public static final int BACKUP_ITERATIONS = 8;

	private final SecureRandom secureRandom = new SecureRandom();

	public byte[] deriveKey(char[] password, byte[] salt, Argon2Params params) {
		try {
			return deriveKeyArgon2id(password, salt, params);
		} catch (Exception e) {
			throw new RuntimeException("Failed to derive key", e);
		}
	}

	private byte[] deriveKeyArgon2id(char[] password, byte[] salt,
			Argon2Params params) {
		ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(
				CharBuffer.wrap(password));
		byte[] passwordBytes = new byte[byteBuffer.remaining()];
		byteBuffer.get(passwordBytes);
		try {
			Argon2Parameters bcParams =
					new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
							.withMemoryAsKB(params.memoryKb)
							.withIterations(params.iterations)
							.withParallelism(params.parallelism)
							.withSalt(salt)
							.build();
			Argon2BytesGenerator generator = new Argon2BytesGenerator();
			generator.init(bcParams);
			byte[] output = new byte[params.hashLength];
			generator.generateBytes(passwordBytes, output);
			return output;
		} finally {
			Arrays.fill(passwordBytes, (byte) 0);
			Arrays.fill(byteBuffer.array(), (byte) 0);
		}
	}

	public byte[] generateSalt() {
		byte[] salt = new byte[DEFAULT_SALT_LENGTH];
		secureRandom.nextBytes(salt);
		return salt;
	}

	public byte[] generateSalt(int length) {
		byte[] salt = new byte[length];
		secureRandom.nextBytes(salt);
		return salt;
	}

	public static void clearPassword(char[] password) {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
	}

	public static void clearBytes(byte[] bytes) {
		if (bytes != null) {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	public static byte[] toBytes(char[] chars) {
		CharBuffer charBuffer = CharBuffer.wrap(chars);
		ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
		byte[] bytes = Arrays.copyOfRange(byteBuffer.array(),
				byteBuffer.position(), byteBuffer.limit());
		Arrays.fill(byteBuffer.array(), (byte) 0);
		return bytes;
	}

	public static class Argon2Params {
		public final int memoryKb;
		public final int iterations;
		public final int parallelism;
		public final int hashLength;

		public Argon2Params(int memoryKb, int iterations, int parallelism,
				int hashLength) {
			this.memoryKb = memoryKb;
			this.iterations = iterations;
			this.parallelism = parallelism;
			this.hashLength = hashLength;
		}

		public static Argon2Params getDefault() {
			return new Argon2Params(
					DEFAULT_MEMORY_KB,
					DEFAULT_ITERATIONS,
					DEFAULT_PARALLELISM,
					DEFAULT_HASH_LENGTH
			);
		}

		public static Argon2Params getLowMemory() {
			return new Argon2Params(
					LOW_MEMORY_KB,
					LOW_ITERATIONS,
					DEFAULT_PARALLELISM,
					DEFAULT_HASH_LENGTH
			);
		}

		public static Argon2Params getBackupStrong() {
			return new Argon2Params(
					LOW_MEMORY_KB,
					BACKUP_ITERATIONS,
					DEFAULT_PARALLELISM,
					DEFAULT_HASH_LENGTH
			);
		}

		public byte[] toBytes() {
			ByteBuffer buffer = ByteBuffer.allocate(16);
			buffer.putInt(memoryKb);
			buffer.putInt(iterations);
			buffer.putInt(parallelism);
			buffer.putInt(hashLength);
			return buffer.array();
		}

		public static Argon2Params fromBytes(byte[] bytes) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			return new Argon2Params(
					buffer.getInt(),
					buffer.getInt(),
					buffer.getInt(),
					buffer.getInt()
			);
		}
	}

	public static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a == null || b == null || a.length != b.length) {
			return false;
		}

		int result = 0;
		for (int i = 0; i < a.length; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}
}