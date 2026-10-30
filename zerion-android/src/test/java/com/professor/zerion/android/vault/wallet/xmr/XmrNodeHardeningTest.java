package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XmrNodeHardeningTest {

	private static final String PUBLIC_IP = "203.0.113.10:18081";
	private static final String PUBLIC_HOST = "node.example.org:18089";

	@Test
	public void looseLiteralsAreRefusedNotResolved() {
		for (String spec : new String[]{"1:2:18081", "[1:2]:18081",
				"01.0.0.1:18081", "1.2.3.04:18081", "1.2.3:18081",
				"1.2.3.4.5:18081", "256.1.1.1:18081", "foo.123:18081",
				"123:18081", "[1::2::3]:18081", "[:1:2:3:4:5:6:7]:18081",
				"[1:2:3:4:5:6:7:8:9]:18081", "[12345::1]:18081",
				"[g::1]:18081", "[1:2:3:4:5:6:7:]:18081"}) {
			for (XmrNode.Source src : XmrNode.Source.values()) {
				assertThrows(spec, IllegalArgumentException.class,
						() -> XmrNode.parse(spec, src, false));
			}
		}
	}

	@Test
	public void strictLiteralsAreParsed() {
		assertArrayEquals(new byte[]{10, 0, 0, 1},
				XmrNode.parseIpv4("10.0.0.1"));
		assertArrayEquals(new byte[]{0, 0, 0, 0}, XmrNode.parseIpv4("0.0.0.0"));
		byte[] loop = new byte[16];
		loop[15] = 1;
		assertArrayEquals(loop, XmrNode.parseIpv6("::1"));
		assertArrayEquals(new byte[16], XmrNode.parseIpv6("::"));
		assertNotNull(XmrNode.parseIpv6("2001:db8:0:0:0:0:0:1"));
		assertNotNull(XmrNode.parseIpv6("fe80::"));
		assertNull(XmrNode.parseIpv6("1:2"));
		assertNull(XmrNode.parseIpv4("01.0.0.1"));
		assertEquals(XmrNode.HostType.IP,
				XmrNode.parse("[2001:db8::1]:18081", XmrNode.Source.CUSTOM,
						false).hostType);
		assertEquals(XmrNode.HostType.HOSTNAME,
				XmrNode.parse("node1.example.org:18081", XmrNode.Source.CUSTOM,
						false).hostType);
	}

	@Test
	public void privateRangesComeFromTheParsedBytes() {
		for (String spec : new String[]{"10.1.2.3:1", "127.0.0.1:1",
				"172.16.0.1:1", "172.31.255.255:1", "192.168.0.1:1",
				"169.254.1.1:1", "[::1]:1", "[fc00::1]:1", "[fdff::1]:1",
				"[fe80::1]:1", "[febf::1]:1", "[fec0::1]:1", "localhost:1"}) {
			assertTrue(spec, XmrNode.parse(spec, XmrNode.Source.CUSTOM, false)
					.isPrivateAddress());
		}
		for (String spec : new String[]{"172.15.0.1:1", "172.32.0.1:1",
				"192.169.0.1:1", "11.0.0.1:1", "[::2]:1", "[fb00::1]:1",
				"[2001:db8::1]:1", "[ff02::1]:1", "8.8.8.8:1"}) {
			assertFalse(spec, XmrNode.parse(spec, XmrNode.Source.CUSTOM, false)
					.isPrivateAddress());
		}
	}

	@Test
	public void unverifiedOwnNodeIsNeverTrusted() {
		XmrNode pub = XmrNode.parse(PUBLIC_HOST, XmrNode.Source.USER_OWNED,
				true);
		assertFalse(pub.trusted);
		assertTrue(XmrNode.parse("2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t"
				+ "7xyfeadqfuad.onion:18089", XmrNode.Source.USER_OWNED, true)
				.trusted);
		assertTrue(XmrNode.parse("192.168.1.5:18081",
				XmrNode.Source.USER_OWNED, true).trusted);
		String id = pub.endpointId();
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_HOST, true, new ArrayList<>(), "",
				Collections.singletonList(id));
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals("node.example.org", order.get(0).host);
		assertFalse("an acknowledged public node is still not trusted",
				order.get(0).trusted);
	}

	@Test
	public void upgradedPublicNodeIsReportedAsPaused() {
		XmrNodeConfig own = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_IP, false, new ArrayList<>(), "");
		assertTrue(own.isPaused());
		assertEquals("203.0.113.10", own.pausedNode().host);
		assertFalse(own.pausePrompted);
		XmrNodeConfig custom = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM,
				"", false, Arrays.asList(PUBLIC_HOST), "");
		assertTrue(custom.isPaused());
		assertEquals(XmrNode.Source.CUSTOM, custom.pausedNode().source);
		assertFalse(new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				"192.168.1.5:18081", false, new ArrayList<>(), "").isPaused());
		assertFalse(XmrNodeConfig.vettedDefault().isPaused());
	}

	@Test
	public void stopSyncingLeavesNoNodeInsteadOfVetted() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_IP, false, new ArrayList<>(), "");
		assertFalse("default keeps the vetted fallback",
				cfg.toFailoverList().isEmpty());
		XmrNodeConfig stopped = cfg.withPauseChoice(true);
		assertTrue(stopped.pausePrompted);
		assertTrue(stopped.toFailoverList().isEmpty());
		XmrNodeConfig kept = cfg.withPauseChoice(false);
		assertTrue(kept.pausePrompted);
		for (XmrNode n : kept.toFailoverList()) {
			assertEquals(XmrNode.Source.VETTED, n.source);
		}
	}

	@Test
	public void confirmingThePausedNodeUsesIt() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				PUBLIC_IP, false, new ArrayList<>(), "").withPauseChoice(true);
		XmrNode paused = cfg.pausedNode();
		XmrNodeConfig confirmed = cfg.withAcknowledged(
				Collections.singletonList(paused.endpointId()));
		assertFalse(confirmed.isPaused());
		assertFalse(confirmed.stopWhenPaused);
		List<XmrNode> order = confirmed.toFailoverList();
		assertEquals(1, order.size());
		assertEquals("203.0.113.10", order.get(0).host);
	}

	@Test
	public void pauseChoiceSurvivesSaveAndLoad() throws Exception {
		MemoryStore store = new MemoryStore();
		new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "", false,
				Arrays.asList(PUBLIC_HOST), "").withPauseChoice(true)
				.save(store);
		XmrNodeConfig loaded = XmrNodeConfig.load(store);
		assertTrue(loaded.stopWhenPaused);
		assertTrue(loaded.pausePrompted);
		assertTrue(loaded.toFailoverList().isEmpty());
		store.settings = "{\"xmr\":{\"_nodes\":{\"mode\":\"CUSTOM\","
				+ "\"custom\":[\"" + PUBLIC_HOST + "\"]}}}";
		XmrNodeConfig old = XmrNodeConfig.load(store);
		assertFalse("a config from before asks once", old.pausePrompted);
		assertFalse(old.stopWhenPaused);
	}

	private static final class MemoryStore implements XmrStore {
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
