package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class XmrNodeConfigTest {

	private static final String ONION =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";

	@Test
	public void vettedResolvesToTheVettedTorSet() {
		List<XmrNode> order = XmrNodeConfig.vettedDefault().toFailoverList();
		assertEquals(XmrNodeSelector.VETTED_DEFAULT.size(), order.size());
		for (XmrNode n : order) {
			assertTrue(n.isOnion());
			assertTrue(n.usesTor());
			assertFalse("a public node is never trusted", n.trusted);
		}
	}

	@Test
	public void ownNodeIsUsedExclusivelyNoSilentPublicDowngrade() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN, ONION,
				new ArrayList<>(), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals("own node only, no vetted fallback", 1, order.size());
		assertEquals(XmrNode.Source.USER_OWNED, order.get(0).source);
		assertTrue(order.get(0).usesTor());
	}

	@Test
	public void directModeIsExclusiveAndClearnet() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.DIRECT, "",
				new ArrayList<>(), "203.0.113.10:18081");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals("direct is exclusive", 1, order.size());
		assertEquals(XmrNode.Source.DIRECT, order.get(0).source);
		assertFalse("direct does not use Tor", order.get(0).usesTor());
	}

	@Test
	public void customNodesAreUsedOverTor() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "",
				Arrays.asList(ONION), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals(1, order.size());
		assertEquals(XmrNode.Source.CUSTOM, order.get(0).source);
		assertTrue(order.get(0).usesTor());
	}

	/** JNI-02: an own node is untrusted unless the user says so; the flag is
	 *  persisted with the node and read back, never defaulted to trusted. */
	@Test
	public void ownNodeIsUntrustedUnlessExplicitlyMarked() throws Exception {
		XmrNodeConfig plain = new XmrNodeConfig(XmrNodeConfig.Mode.OWN, ONION,
				new ArrayList<>(), "");
		assertFalse(plain.ownTrusted);
		assertFalse(plain.toFailoverList().get(0).trusted);
		XmrNodeConfig trusted = new XmrNodeConfig(XmrNodeConfig.Mode.OWN, ONION,
				true, new ArrayList<>(), "");
		assertTrue(trusted.toFailoverList().get(0).trusted);
		SettingsOnlyStore store = new SettingsOnlyStore();
		trusted.save(store);
		XmrNodeConfig loaded = XmrNodeConfig.load(store);
		assertEquals(XmrNodeConfig.Mode.OWN, loaded.mode);
		assertTrue("trust survives the round trip", loaded.ownTrusted);
		assertTrue(loaded.toFailoverList().get(0).trusted);
		plain.save(store);
		assertFalse(XmrNodeConfig.load(store).ownTrusted);
		store.settings = "{\"xmr\":{\"_nodes\":{\"mode\":\"OWN\",\"own\":\""
				+ ONION + "\"}}}";
		assertFalse("a record without the flag is untrusted",
				XmrNodeConfig.load(store).ownTrusted);
	}

	@Test
	public void customNodesAreNeverTrustedEvenWhenOwnIs() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, ONION,
				true, Arrays.asList(ONION), "");
		for (XmrNode n : cfg.toFailoverList()) assertFalse(n.trusted);
	}

	private static final class SettingsOnlyStore implements XmrStore {
		String settings;
		private final Object monitor = new Object();

		@Override
		public String createWallet(com.professor.zerion.android.vault.wallet.WalletCoin coin,
				String name, char[] mnemonic, char[] password) {
			throw new UnsupportedOperationException();
		}

		@Override
		public char[] loadMnemonicChars(String walletId, char[] password) {
			throw new UnsupportedOperationException();
		}

		@Override
		public java.util.List<com.professor.zerion.android.vault.wallet.WalletRecord> listWallets() {
			return new ArrayList<>();
		}

		@Override
		public void deleteWallet(String walletId) {
		}

		@Override
		public String readSettings() {
			return settings;
		}

		@Override
		public void writeSettings(String json) {
			settings = json;
		}

		@Override
		public Object settingsMonitor() {
			return monitor;
		}

		private final java.util.Map<String, byte[]> walletSecrets =
				new java.util.HashMap<>();

		@Override
		@Nullable
		public byte[] readWalletSecret(String walletId, String name) {
			byte[] v = walletSecrets.get(walletId + "/" + name);
			return v == null ? null : v.clone();
		}

		@Override
		public void writeWalletSecret(String walletId, String name,
				byte[] value) {
			walletSecrets.put(walletId + "/" + name, value.clone());
		}

		@Override
		public void removeWalletSecret(String walletId, String name) {
			walletSecrets.remove(walletId + "/" + name);
		}

		@Override
		public String readSpendJournal(String walletId) {
			return null;
		}

		@Override
		public void writeSpendJournal(String walletId, String journal) {
		}

		@Override
		public void removeSpendJournal(String walletId) {
		}
	}

	@Test
	public void malformedNodeFallsBackToVettedNeverEmpty() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				"not a node", new ArrayList<>(), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertFalse("a malformed own node falls back to a safe set, never empty",
				order.isEmpty());
		for (XmrNode n : order) assertTrue(n.usesTor());
	}
}
