package org.briarproject.bramble.crypto;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.StringUtils;

import java.util.logging.Logger;

import javax.inject.Inject;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logDuration;
import static org.briarproject.bramble.util.LogUtils.now;

/**
 * Argon2id Key Derivation Function - Post-Quantum resistant password hashing.
 * <p>
 * Argon2id is the winner of the Password Hashing Competition (2015) and combines:
 * - Memory-hardness (resists GPU/ASIC attacks)
 * - Time-hardness (resists parallel attacks)
 * - Data-dependent addressing (resists side-channel attacks)
 * <p>
 * This implementation uses Argon2id variant which provides the best balance
 * of security against both side-channel and GPU attacks.
 * <p>
 * Post-Quantum Consideration: Symmetric key derivation functions like Argon2id
 * are not affected by quantum computers. Grover's algorithm only halves the
 * effective security, so 256-bit output maintains 128-bit post-quantum security.
 */
class Argon2idKdf implements PasswordBasedKdf {

	private static final Logger LOG = getLogger(Argon2idKdf.class.getName());

	// Argon2id parameters
	// Memory cost in KB - optimized for mobile devices
	// Lower memory = faster calibration, still very secure
	private static final int MIN_MEMORY_KB = 64 * 1024;      // 64 MB minimum
	private static final int DEFAULT_MEMORY_KB = 128 * 1024; // 128 MB default (reduced from 256)
	private static final int MAX_MEMORY_KB = 256 * 1024;     // 256 MB maximum (reduced from 512)

	// Iterations (time cost)
	private static final int MIN_ITERATIONS = 2;
	private static final int DEFAULT_ITERATIONS = 3;
	private static final int MAX_ITERATIONS = 4; // Reduced from 6 for faster mobile performance

	// Parallelism (number of threads)
	private static final int PARALLELISM = 1; // Single thread for mobile compatibility

	// Target derivation time in milliseconds - reduced for better UX
	private static final int TARGET_MS = 500; // Reduced from 1000ms

	private final Clock clock;

	@Inject
	Argon2idKdf(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Choose the cost parameter (memory in KB) based on device capabilities.
	 * The returned value encodes both memory and iterations.
	 * <p>
	 * Format: cost = (memory_kb << 8) | iterations
	 * <p>
	 * Optimized for fast calibration on mobile devices - uses default values
	 * with minimal probing to avoid long startup times.
	 */
	@Override
	public int chooseCostParameter() {
		// Check available memory - use at most 1/4 of max heap
		long maxMemory = Runtime.getRuntime().maxMemory();
		int maxMemoryKb = (int) min(MAX_MEMORY_KB, maxMemory / 4096);
		maxMemoryKb = max(MIN_MEMORY_KB, maxMemoryKb);

		if (LOG.isLoggable(INFO)) {
			LOG.info("Argon2id calibration: max heap=" + maxMemory +
					", max memory for KDF=" + maxMemoryKb + " KB");
		}

		// Use default memory, capped by available memory
		// This avoids expensive iterative calibration
		int memoryKb = min(DEFAULT_MEMORY_KB, maxMemoryKb);
		int iterations = DEFAULT_ITERATIONS;

		// Single test to see if we can use higher memory (one probe only)
		if (memoryKb < maxMemoryKb) {
			int higherMemory = min(memoryKb * 2, maxMemoryKb);
			long duration = measureDuration(higherMemory, iterations);
			if (duration <= TARGET_MS) {
				memoryKb = higherMemory;
			}
		}

		// Encode as combined cost parameter (memory in upper 24 bits, iterations in lower 8 bits)
		int cost = (memoryKb << 8) | iterations;

		if (LOG.isLoggable(INFO)) {
			LOG.info("Argon2id parameters: memory=" + memoryKb +
					" KB, iterations=" + iterations + ", encoded cost=" + cost);
		}

		return cost;
	}

	/**
	 * Decode cost parameter to extract memory (KB).
	 */
	static int decodeMemoryKb(int cost) {
		return cost >> 8;
	}

	/**
	 * Decode cost parameter to extract iterations.
	 */
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
	public SecretKey deriveKey(String password, byte[] salt, int cost) {
		long start = now();

		// Decode cost parameter
		int memoryKb = decodeMemoryKb(cost);
		int iterations = decodeIterations(cost);

		// Validate parameters
		if (memoryKb < MIN_MEMORY_KB) memoryKb = MIN_MEMORY_KB;
		if (memoryKb > MAX_MEMORY_KB) memoryKb = MAX_MEMORY_KB;
		if (iterations < MIN_ITERATIONS) iterations = MIN_ITERATIONS;
		if (iterations > MAX_ITERATIONS) iterations = MAX_ITERATIONS;

		byte[] passwordBytes = StringUtils.toUtf8(password);

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

		SecretKey key = new SecretKey(output);
		logDuration(LOG, "Deriving key from password (Argon2id, mem=" +
				memoryKb + "KB, iter=" + iterations + ")", start);

		return key;
	}

	/**
	 * Create a valid cost parameter from explicit memory and iterations values.
	 * Used for migration and testing.
	 */
	public static int encodeCostParameter(int memoryKb, int iterations) {
		return (memoryKb << 8) | (iterations & 0xFF);
	}
}
