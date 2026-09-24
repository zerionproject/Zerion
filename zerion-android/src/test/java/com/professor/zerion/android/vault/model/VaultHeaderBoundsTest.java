package com.professor.zerion.android.vault.model;

import com.professor.zerion.android.vault.crypto.Argon2;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * STO-04: a vault header read from disk or an import is bounded before any
 * allocation or derivation happens. Absurd KDF parameters and field lengths
 * are refused with an argument error, never with an out-of-memory crash.
 */
public class VaultHeaderBoundsTest {

	private static byte[] bytes(int n, int fill) {
		byte[] b = new byte[n];
		java.util.Arrays.fill(b, (byte) fill);
		return b;
	}

	private static byte[] header(int memoryKb, int iterations) {
		return VaultHeader.createNew(bytes(32, 1), memoryKb, iterations,
				bytes(60, 2), bytes(16, 3), bytes(32, 4)).toBytes();
	}

	@Test
	public void theParametersTheAppWritesRoundTrip() {
		VaultHeader h = VaultHeader.fromBytes(
				header(Argon2.DEFAULT_MEMORY_KB, Argon2.DEFAULT_ITERATIONS));
		assertEquals(Argon2.DEFAULT_MEMORY_KB, h.kdfMemoryKb);
		VaultHeader low = VaultHeader.fromBytes(
				header(Argon2.LOW_MEMORY_KB, Argon2.LOW_ITERATIONS));
		assertEquals(Argon2.LOW_ITERATIONS, low.kdfIterations);
	}

	@Test
	public void oversizedKdfParametersAreRefused() {
		try {
			VaultHeader.fromBytes(header(Integer.MAX_VALUE, 3));
			fail("2 TB of memory must be refused");
		} catch (IllegalArgumentException expected) {
		}
		try {
			VaultHeader.fromBytes(header(Argon2.MAX_MEMORY_KB + 1, 3));
			fail("above the largest value the app writes");
		} catch (IllegalArgumentException expected) {
		}
		try {
			VaultHeader.fromBytes(header(Argon2.DEFAULT_MEMORY_KB, 11));
			fail("eleven passes");
		} catch (IllegalArgumentException expected) {
		}
		try {
			VaultHeader.fromBytes(header(512, 3));
			fail("below the minimum");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void oversizedFieldLengthsAreRefusedBeforeAllocation() {
		byte[] valid = header(Argon2.DEFAULT_MEMORY_KB, 3);
		ByteBuffer b = ByteBuffer.wrap(valid.clone());
		b.putInt(8, Integer.MAX_VALUE);
		try {
			VaultHeader.fromBytes(b.array());
			fail("a 2 GB salt length must be refused");
		} catch (IllegalArgumentException expected) {
		}
		ByteBuffer negative = ByteBuffer.wrap(valid.clone());
		negative.putInt(8, -1);
		try {
			VaultHeader.fromBytes(negative.array());
			fail("a negative length must be refused");
		} catch (IllegalArgumentException expected) {
		}
	}
}
