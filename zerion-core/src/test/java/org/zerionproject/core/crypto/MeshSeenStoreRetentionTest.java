package org.zerionproject.core.crypto;

import org.zerionproject.core.crypto.async.MeshSeenStore;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeshSeenStoreRetentionTest {

	@Test
	public void anIdIsStillKnownAfterTwoThousandNewerIdsTheSameDay()
			throws Exception {
		MeshSeenStore store = new MeshSeenStore(new InMemorySettingsManager());
		byte[] first = id(-1);
		assertFalse(store.checkAndMark(first));
		for (int i = 0; i < 2000; i++) assertFalse(store.checkAndMark(id(i)));
		assertTrue("the first message would be delivered again",
				store.checkAndMark(first));
	}

	private static byte[] id(int n) {
		return ByteBuffer.allocate(32).putInt(7).putInt(n).array();
	}
}
