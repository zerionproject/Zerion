package com.professor.zerion.android;

import org.junit.Test;
import org.zerionproject.core.account.AndroidAccountManager;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.transport.BridgeHealth;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ZerionTorModuleBridgeTest {

	@Test
	public void bridgeHealthBelongsToTheSignedInProfile() {
		AndroidAccountManager accounts = mock(AndroidAccountManager.class);
		when(accounts.getActiveProfileId()).thenReturn("profile-a");
		BridgeHealth.Owner owner =
				new ZerionTorModule().provideBridgeHealthOwner(accounts);
		assertEquals("profile-a", owner.id());
		when(accounts.getActiveProfileId()).thenReturn("profile-imported");
		assertEquals("an imported copy is a new profile", "profile-imported",
				owner.id());
	}

	@Test
	public void downloadsAreCheckedWithThePinnedKeyAlone() {
		BridgeListKeys keys = new ZerionTorModule().provideBridgeListKeys();
		assertEquals(1, keys.forNetwork().size());
	}
}
