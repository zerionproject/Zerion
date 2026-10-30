package com.professor.zerion.android.mesh;

import org.junit.Test;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MeshBluetoothNameTest {

	private final MeshTestSettings settings = new MeshTestSettings();

	@Test
	public void everySessionOfEveryDeviceShowsTheSameName() throws Exception {
		FakeAdapter first = new FakeAdapter("Alice's Pixel", false);
		FakeAdapter second = new FakeAdapter("Bob's Moto", false);
		MeshBluetoothName.mask(first, settings);
		MeshBluetoothName.mask(second, new MeshTestSettings());
		assertEquals(first.name, second.name);
		MeshBluetoothName.restore(first, settings);
		MeshBluetoothName.mask(first, settings);
		assertEquals(MeshBluetoothName.MASKED_NAME, first.name);
	}

	@Test
	public void theOwnNameComesBackAndIsForgotten() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		MeshBluetoothName.mask(adapter, settings);
		MeshBluetoothName.mask(adapter, settings);
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
		assertEquals("", settings.getSettings(MeshBluetoothName.NAMESPACE)
				.get(MeshBluetoothName.ORIGINAL_NAME_KEY));
	}

	@Test
	public void aNameMaskedByAnEarlierReleaseIsRestored() throws Exception {
		FakeAdapter adapter = new FakeAdapter("BT-3fa2c1", false);
		org.zerionproject.core.api.settings.Settings s =
				new org.zerionproject.core.api.settings.Settings();
		s.put(MeshBluetoothName.ORIGINAL_NAME_KEY, "Alice's Pixel");
		settings.mergeSettings(s, MeshBluetoothName.NAMESPACE);
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void aNameTheUserChoseMeanwhileIsKept() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		MeshBluetoothName.mask(adapter, settings);
		adapter.name = "Alice";
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice", adapter.name);
	}

	@Test
	public void maskingWaitsUntilTheAdapterShowsTheName() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", true);
		MeshBluetoothName.mask(adapter, settings);
		assertTrue(adapter.reads >= 2);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.getName());
	}

	@Test
	public void aRestoreThatFailsIsTriedAgainLater() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		MeshBluetoothName.mask(adapter, settings);
		adapter.refuse = true;
		MeshBluetoothName.restore(adapter, settings);
		assertEquals(MeshBluetoothName.MASKED_NAME, adapter.name);
		adapter.refuse = false;
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void theChosenAliasIsShownInsteadOfTheRealName() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("Kitchen radio");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("Kitchen radio", adapter.name);
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void anAliasChangedWhileHiddenIsStillRecognised() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("Kitchen radio");
		MeshBluetoothName.mask(adapter, settings);
		alias("Garden");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("Garden", adapter.name);
		alias("Shed");
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void anOldAliasLeftByAFailedRestoreIsNotSavedAsTheRealName()
			throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("Kitchen radio");
		MeshBluetoothName.mask(adapter, settings);
		adapter.refuse = true;
		MeshBluetoothName.restore(adapter, settings);
		adapter.refuse = false;
		alias("Garden");
		MeshBluetoothName.mask(adapter, settings);
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void anAliasChangedWhileTheAdapterRefusesIsStillRecognised()
			throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("Kitchen radio");
		MeshBluetoothName.mask(adapter, settings);
		adapter.refuse = true;
		alias("Garden");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("Kitchen radio", adapter.name);
		adapter.refuse = false;
		MeshBluetoothName.restore(adapter, settings);
		assertEquals("Alice's Pixel", adapter.name);
	}

	@Test
	public void hiddenAndControlCharactersAreRemovedFromTheAlias()
			throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("\u202eKit\u0000chen\u200b\n radio\u2028");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("Kitchen radio", adapter.name);
	}

	@Test
	public void theAliasIsCutAtThirtyTwoCharacters() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias("abcdefghijklmnopqrstuvwxyz0123456789");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("abcdefghijklmnopqrstuvwxyz012345", adapter.name);
	}

	@Test
	public void anEmptyAliasFallsBackToAndroid() throws Exception {
		FakeAdapter adapter = new FakeAdapter("Alice's Pixel", false);
		alias(" \u200b\t ");
		MeshBluetoothName.mask(adapter, settings);
		assertEquals("Android", adapter.name);
	}

	private void alias(String value) {
		org.zerionproject.core.api.settings.Settings s =
				new org.zerionproject.core.api.settings.Settings();
		s.put("btAlias", value);
		settings.mergeSettings(s, MeshBluetoothName.NAMESPACE);
	}

	private static final class FakeAdapter
			implements MeshBluetoothName.Adapter {
		String name;
		final boolean slow;
		boolean refuse = false;
		int reads = 0;
		private int pendingReads = 0;

		FakeAdapter(String name, boolean slow) {
			this.name = name;
			this.slow = slow;
		}

		@Nullable
		@Override
		public String getName() {
			reads++;
			if (pendingReads > 0) {
				pendingReads--;
				return "Alice's Pixel";
			}
			return name;
		}

		@Override
		public boolean setName(String n) {
			if (refuse) return false;
			name = n;
			if (slow) pendingReads = 2;
			return true;
		}
	}
}
