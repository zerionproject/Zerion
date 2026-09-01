package com.professor.zerion.android.vault.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VaultItemKdfParamsTest {

	private static byte[] bytes(int n, int fill) {
		byte[] b = new byte[n];
		java.util.Arrays.fill(b, (byte) fill);
		return b;
	}

	@Test
	public void passwordItemRoundTripsCustomKdfParams() {
		VaultItem item = VaultItem.createNewWithPassword(
				VaultItem.ItemType.WALLET, "BTC\nMain", 32,
				bytes(48, 1), bytes(12, 2), bytes(32, 3),
				64 * 1024, 3, 1);
		assertEquals(2, item.version);

		VaultItem restored =
				VaultItem.deserializeMetadata(item.serializeMetadata());

		assertTrue(restored.hasExtraPassword);
		assertEquals(64 * 1024, restored.extraPasswordMemoryKb);
		assertEquals(3, restored.extraPasswordIterations);
		assertEquals(1, restored.extraPasswordParallelism);
	}

	@Test
	public void legacyVersionOneDefaultsTo256MbParams() {
		VaultItem legacy = VaultItem.createNew(
				VaultItem.ItemType.WALLET, "BTC\nOld", 32,
				bytes(48, 1), bytes(12, 2));
		assertEquals(1, legacy.version);

		VaultItem restored =
				VaultItem.deserializeMetadata(legacy.serializeMetadata());

		assertEquals("existing items must keep their original 256 MB KDF",
				VaultItem.DEFAULT_EXTRA_MEMORY_KB,
				restored.extraPasswordMemoryKb);
		assertEquals(VaultItem.DEFAULT_EXTRA_ITERATIONS,
				restored.extraPasswordIterations);
		assertEquals(VaultItem.DEFAULT_EXTRA_PARALLELISM,
				restored.extraPasswordParallelism);
	}

	@Test
	public void walletPasswordPresetIsLighterThanVaultDefault() {
		com.professor.zerion.android.vault.crypto.Argon2.Argon2Params wallet =
				com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
						.getWalletPassword();
		com.professor.zerion.android.vault.crypto.Argon2.Argon2Params def =
				com.professor.zerion.android.vault.crypto.Argon2.Argon2Params
						.getDefault();
		assertTrue("wallet KDF memory must be lighter to avoid OOM on low-RAM"
				+ " devices", wallet.memoryKb < def.memoryKb);
		assertTrue("wallet KDF must still be a strong Argon2id profile",
				wallet.memoryKb >= 32 * 1024 && wallet.iterations >= 3);
	}
}
