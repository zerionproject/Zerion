package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.generators.SCrypt;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.system.Clock;
import javax.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import static java.lang.Math.min;

class ScryptKdf implements PasswordBasedKdf {
	private static final int MIN_COST = 256;
	private static final int MAX_COST = 1024 * 1024;
	private static final int BLOCK_SIZE = 8;
	private static final int PARALLELIZATION = 1;
	private static final int TARGET_MS = 1000;

	private final Clock clock;

	@Inject
	ScryptKdf(Clock clock) {
		this.clock = clock;
	}

	@Override
	public int chooseCostParameter() {
		long maxMemory = Runtime.getRuntime().maxMemory();
		long maxCost = min(MAX_COST, maxMemory / BLOCK_SIZE / 256);
		int cost = MIN_COST;
		while (cost * 2 <= maxCost && measureDuration(cost) * 2 <= TARGET_MS) {
			cost *= 2;
		}
		return cost;
	}

	private long measureDuration(int cost) {
		byte[] password = new byte[16], salt = new byte[32];
		long start = clock.currentTimeMillis();
		SCrypt.generate(password, salt, cost, BLOCK_SIZE, PARALLELIZATION,
				SecretKey.LENGTH);
		return clock.currentTimeMillis() - start;
	}

	@Override
	public SecretKey deriveKey(char[] password, byte[] salt, int cost) {
		ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(
				CharBuffer.wrap(password));
		byte[] passwordBytes = new byte[byteBuffer.remaining()];
		byteBuffer.get(passwordBytes);
		try {
			return new SecretKey(SCrypt.generate(passwordBytes, salt, cost,
					BLOCK_SIZE, PARALLELIZATION, SecretKey.LENGTH));
		} finally {

			java.util.Arrays.fill(passwordBytes, (byte) 0);
			java.util.Arrays.fill(byteBuffer.array(), (byte) 0);
		}
	}
}
