package com.professor.zerion.android.mesh;

import org.junit.Test;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;

public class BluetoothNameGuardTest {

	private final MeshTestSettings settings = new MeshTestSettings();
	private final FakeAdapter adapter = new FakeAdapter("Alice's Pixel");

	@Test
	public void pairNearbyHidesTheNameAndGivesItBack() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
		guard.release(BluetoothNameGuard.HOLDER_PAIRING);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void theMeshAndPairNearbyDoNotFightOverTheName() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_MESH);
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard.release(BluetoothNameGuard.HOLDER_PAIRING);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard.release(BluetoothNameGuard.HOLDER_MESH);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
		guard.release(BluetoothNameGuard.HOLDER_PAIRING);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void aStartupRestoreLeavesANameInUseAlone() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard.restoreIfUnheld();
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
	}

	@Test
	public void aNameLeftHiddenByAnInterruptedPairingComesBackOnNextStart() {
		guard().acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard().restoreIfUnheld();
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void stoppingTheMeshStillRestoresWhenNothingWasHeld() {
		guard().acquire(BluetoothNameGuard.HOLDER_MESH);
		guard().release(BluetoothNameGuard.HOLDER_MESH);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void noAdapterIsHarmless() {
		BluetoothNameGuard guard =
				new BluetoothNameGuard(settings, () -> null);
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard.release(BluetoothNameGuard.HOLDER_PAIRING);
		guard.restoreIfUnheld();
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void aNewAliasIsShownAtOnceWhileTheNameIsHidden() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_MESH);
		org.zerionproject.core.api.settings.Settings s =
				new org.zerionproject.core.api.settings.Settings();
		s.put("btAlias", "Kitchen radio");
		settings.mergeSettings(s, MeshBluetoothName.NAMESPACE);
		guard.reapply();
		assertEquals("Kitchen radio", adapter.name);
		guard.release(BluetoothNameGuard.HOLDER_MESH);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void aNewAliasDoesNotHideTheNameWhenNothingRuns() {
		guard().reapply();
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void aRestoreThatFailedIsRetriedWhenBluetoothComesOn() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_MESH);
		adapter.refuse = true;
		guard.release(BluetoothNameGuard.HOLDER_MESH);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
		adapter.refuse = false;
		guard.onBluetoothOn();
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void bluetoothComingOnLeavesANameInUseAlone() {
		BluetoothNameGuard guard = guard();
		guard.acquire(BluetoothNameGuard.HOLDER_MESH);
		adapter.refuse = true;
		guard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		guard.release(BluetoothNameGuard.HOLDER_MESH);
		adapter.refuse = false;
		guard.onBluetoothOn();
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
	}

	private BluetoothNameGuard guard() {
		return new BluetoothNameGuard(settings, () -> adapter);
	}

	static final class FakeAdapter implements MeshBluetoothName.Adapter {
		String name;
		boolean refuse = false;

		FakeAdapter(String name) {
			this.name = name;
		}

		@Nullable
		@Override
		public String getName() {
			return name;
		}

		@Override
		public boolean setName(String n) {
			if (refuse) return false;
			name = n;
			return true;
		}
	}
}
