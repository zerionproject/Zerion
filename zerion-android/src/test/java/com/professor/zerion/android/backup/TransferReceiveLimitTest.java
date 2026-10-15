package com.professor.zerion.android.backup;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransferReceiveLimitTest {

	private static final long MIB = 1024 * 1024;

	@Test
	public void theReceiverTakesOnlyWhatFourCopiesOfFitInItsHeap() {
		long heap = 512 * MIB;
		assertFalse(AccountTransferManager.receivable(0, heap));
		assertFalse(AccountTransferManager.receivable(-1, heap));
		assertTrue(AccountTransferManager.receivable(1, heap));
		assertTrue(AccountTransferManager.receivable((int) (96 * MIB), heap));
		assertFalse(AccountTransferManager.receivable((int) (96 * MIB) + 1,
				heap));
		assertFalse(AccountTransferManager.receivable(Integer.MAX_VALUE,
				8192 * MIB));
		assertTrue(AccountTransferManager.receivable((int) (512 * MIB),
				8192 * MIB));
		assertFalse(AccountTransferManager.receivable((int) (512 * MIB) + 1,
				8192 * MIB));
	}
}
