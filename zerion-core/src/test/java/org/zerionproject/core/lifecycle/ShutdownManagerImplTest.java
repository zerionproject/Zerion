package org.zerionproject.core.lifecycle;

import org.zerionproject.core.api.lifecycle.ShutdownManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShutdownManagerImplTest extends BrambleTestCase {

	@Test
	public void testAddAndRemove() {
		ShutdownManager s = createShutdownManager();
		Set<Integer> handles = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			int handle = s.addShutdownHook(() -> {});

			assertTrue(handles.add(handle));
		}

		for (int handle : handles) assertTrue(s.removeShutdownHook(handle));

		for (int handle : handles) assertFalse(s.removeShutdownHook(handle));
	}

	protected ShutdownManager createShutdownManager() {
		return new ShutdownManagerImpl();
	}
}
