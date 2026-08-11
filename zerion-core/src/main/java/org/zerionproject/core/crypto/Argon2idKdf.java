package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.system.Clock;
import javax.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import static java.lang.Math.max;
import static java.lang.Math.min;

class Argon2idKdf implements PasswordBasedKdf {
	private static final int MIN_MEMORY_KB = 64 * 1024;
	private static final int DEFAULT_MEMORY_KB = 128 * 1024;
	private static final int MAX_MEMORY_KB = 256 * 1024;
	private static final int MIN_ITERATIONS = 2;
	private static final int DEFAULT_ITERATIONS = 3;
	private static final int MAX_ITERATIONS = 4;
	private static final int PARALLELISM = 1;
	private static final int TARGET_MS = 500;

	private final Clock clock;

	@Inject
	Argon2idKdf(Clock clock) {
		this.clock = clock;
	}

	@Override
	public int chooseCostParameter() {
		long maxMemory = Runtime.getRuntime().maxMemory();
		int maxMemoryKb = (int) min(MAX_MEMORY_KB, maxMemory / 4096);
		maxMemoryKb = max(MIN_MEMORY_KB, maxMemoryKb);
		int memoryKb = min(DEFAULT_MEMORY_KB, maxMemoryKb);
		int iterations = DEFAULT_ITERATIONS;
		if (memoryKb < maxMemoryKb) {
			int higherMemory = min(memoryKb * 2, maxMemoryKb);
			long duration = measureDuration(higherMemory, iterations);
			if (duration <= TARGET_MS) {
				memoryKb = higherMemory;
			}
		}
		int cost = (memoryKb << 8) | iterations;
		return cost;
	}

	static int decodeMemoryKb(int cost) {
		return cost >> 8;
	}

	static int decodeIterations(int cost) {
		return cost & 0xFF;
	}

	private long measureDuration(int memoryKb, int iterations) {
		byte[] password = new byte[16];
		byte[] salt = new byte[32];
		long start = clock.currentTimeMillis();

		Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
				.withMemoryAsKB(memoryKb)
				.withIterations(iterations)
				.withParallelism(PARALLELISM)
				.withSalt(salt)
				.build();

		Argon2BytesGenerator generator = new Argon2BytesGenerator();
		generator.init(params);
		byte[] output = new byte[SecretKey.LENGTH];
		generator.generateBytes(password, output);

		return clock.currentTimeMillis() - start;
	}

	@Override
	public SecretKey deriveKey(char[] password, byte[] salt, int cost) {
		int memoryKb = decodeMemoryKb(cost);
		int iterations = decodeIterations(cost);
		if (memoryKb < MIN_MEMORY_KB) memoryKb = MIN_MEMORY_KB;
		if (memoryKb > MAX_MEMORY_KB) memoryKb = MAX_MEMORY_KB;
		if (iterations < MIN_ITERATIONS) iterations = MIN_ITERATIONS;
		if (iterations > MAX_ITERATIONS) iterations = MAX_ITERATIONS;

		ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(
				CharBuffer.wrap(password));
		byte[] passwordBytes = new byte[byteBuffer.remaining()];
		byteBuffer.get(passwordBytes);

		try {
			Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
					.withMemoryAsKB(memoryKb)
					.withIterations(iterations)
					.withParallelism(PARALLELISM)
					.withSalt(salt)
					.build();

			Argon2BytesGenerator generator = new Argon2BytesGenerator();
			generator.init(params);

			byte[] output = new byte[SecretKey.LENGTH];
			generator.generateBytes(passwordBytes, output);

			return new SecretKey(output);
		} finally {

			java.util.Arrays.fill(passwordBytes, (byte) 0);
			java.util.Arrays.fill(byteBuffer.array(), (byte) 0);
		}
	}

	public static int encodeCostParameter(int memoryKb, int iterations) {
		return (memoryKb << 8) | (iterations & 0xFF);
	}
}
