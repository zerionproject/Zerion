package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XmrUnverifiedNodeTest {

	private static final String ONION =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";
	private static final String PUBLIC_IP = "203.0.113.10:18081";
	private static final String PUBLIC_HOST = "node.example.org:18089";

	private static boolean containsHost(List<XmrNode> order, String host) {
		for (XmrNode n : order) {
			if (n.host.equals(host)) return true;
		}
		return false;
	}

	private static void assertOnlyVetted(List<XmrNode> order) {
		assertEquals(XmrNodeSelector.VETTED_DEFAULT.size(), order.size());
		for (XmrNode n : order) {
			assertTrue(n.isOnion());
			assertEquals(XmrNode.Source.VETTED, n.source);
		}
	}

	@Test
	public void unacknowledgedPublicCustomNodeIsRefused() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "",
				false, Arrays.asList(PUBLIC_IP), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertFalse(containsHost(order, "203.0.113.10"));
		assertOnlyVetted(order);
	}

	@Test
	public void unacknowledgedPublicHostnameOwnNodeIsRefused() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_HOST, true, new ArrayList<>(), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertFalse(containsHost(order, "node.example.org"));
		for (XmrNode n : order) assertFalse(n.trusted);
		assertOnlyVetted(order);
	}

	@Test
	public void acknowledgedPublicNodeIsUsedOverTor() {
		String id = XmrNode.parse(PUBLIC_HOST, XmrNode.Source.CUSTOM, false)
				.endpointId();
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "",
				false, Arrays.asList(PUBLIC_HOST), "",
				Collections.singletonList(id));
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals(1, order.size());
		assertEquals("node.example.org", order.get(0).host);
		assertTrue(order.get(0).usesTor());
	}

	@Test
	public void acknowledgementIsPerNode() {
		String other = XmrNode.parse("198.51.100.7:18081",
				XmrNode.Source.CUSTOM, false).endpointId();
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "",
				false, Arrays.asList(PUBLIC_IP), "",
				Collections.singletonList(other));
		assertOnlyVetted(cfg.toFailoverList());
		String samePortOther = XmrNode.parse("203.0.113.10:18089",
				XmrNode.Source.CUSTOM, false).endpointId();
		cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "", false,
				Arrays.asList(PUBLIC_IP), "",
				Collections.singletonList(samePortOther));
		assertOnlyVetted(cfg.toFailoverList());
	}

	@Test
	public void onionAndLanNodesNeedNoAcknowledgement() {
		String[] specs = {ONION, "192.168.1.20:18081", "10.0.0.5:18089",
				"172.16.4.4:18081", "127.0.0.1:18081", "169.254.3.3:18081",
				"[::1]:18081", "[fd12:3456::1]:18081", "[fe80::1]:18081",
				"localhost:18081"};
		for (String spec : specs) {
			XmrNode n = XmrNode.parse(spec, XmrNode.Source.CUSTOM, false);
			assertFalse(spec, n.isUnverifiedOverTor());
			XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM,
					"", false, Arrays.asList(spec), "");
			List<XmrNode> order = cfg.toFailoverList();
			assertEquals(spec, 1, order.size());
			assertEquals(spec, n.host, order.get(0).host);
			XmrNodeConfig own = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
					spec, false, new ArrayList<>(), "");
			assertEquals(spec, n.host, own.toFailoverList().get(0).host);
		}
	}

	@Test
	public void publicAddressesNeedAcknowledgement() {
		String[] specs = {PUBLIC_IP, PUBLIC_HOST, "172.32.0.1:18081",
				"[2001:db8::5]:18081", "8.8.8.8:18089"};
		for (String spec : specs) {
			assertTrue(spec, XmrNode.parse(spec, XmrNode.Source.CUSTOM, false)
					.isUnverifiedOverTor());
			assertTrue(spec, XmrNode.parse(spec, XmrNode.Source.USER_OWNED,
					false).isUnverifiedOverTor());
		}
	}

	@Test
	public void directNodeIsUnchanged() {
		XmrNode d = XmrNode.parse(PUBLIC_IP, XmrNode.Source.DIRECT, false);
		assertFalse(d.isUnverifiedOverTor());
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.DIRECT, "",
				new ArrayList<>(), PUBLIC_IP);
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals(1, order.size());
		assertEquals(XmrNode.Source.DIRECT, order.get(0).source);
	}

	@Test
	public void acknowledgementSurvivesSaveAndLoad() throws Exception {
		String id = XmrNode.parse(PUBLIC_IP, XmrNode.Source.USER_OWNED, false)
				.endpointId();
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_IP, false, new ArrayList<>(), "",
				Collections.singletonList(id));
		MemorySettingsStore store = new MemorySettingsStore();
		cfg.save(store);
		XmrNodeConfig loaded = XmrNodeConfig.load(store);
		assertEquals(Collections.singletonList(id), loaded.unverifiedAcks);
		assertEquals("203.0.113.10", loaded.toFailoverList().get(0).host);
		store.settings = "{\"xmr\":{\"_nodes\":{\"mode\":\"OWN\",\"own\":\""
				+ PUBLIC_IP + "\"}}}";
		assertOnlyVetted(XmrNodeConfig.load(store).toFailoverList());
	}

	private static final class MemorySettingsStore implements XmrStore {
		String settings;
		private final Object monitor = new Object();

		@Override
		public String createWallet(
				com.professor.zerion.android.vault.wallet.WalletCoin coin,
				String name, char[] mnemonic, char[] password) {
			throw new UnsupportedOperationException();
		}

		@Override
		public char[] loadMnemonicChars(String walletId, char[] password) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<com.professor.zerion.android.vault.wallet.WalletRecord> listWallets() {
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

		@Override
		public byte[] readWalletSecret(String walletId, String name) {
			return null;
		}

		@Override
		public void writeWalletSecret(String walletId, String name,
				byte[] value) {
		}

		@Override
		public void removeWalletSecret(String walletId, String name) {
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
}
