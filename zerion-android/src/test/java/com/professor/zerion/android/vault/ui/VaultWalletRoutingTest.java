package com.professor.zerion.android.vault.ui;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.wallet.btc.BtcWallet;
import com.professor.zerion.android.vault.wallet.btc.ElectrumEndpoint;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.await;
import static com.professor.zerion.android.vault.ui.VaultViewModelRig.field;
import static com.professor.zerion.android.vault.ui.VaultViewModelRig.settle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultWalletRoutingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ABANDON = "abandon abandon abandon abandon"
			+ " abandon abandon abandon abandon abandon abandon abandon about";
	private static final String ONION =
			"kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion";
	private static final String USER_ONION =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion";
	private static final String VIA_TOR_DEFAULT =
			"onion|0|" + ONION + ":50001||tor";
	private static final String VIA_TOR_TLS_FALLBACK =
			"tls|0|electrum.blockstream.info:50002||tor";

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static String describe(ElectrumEndpoint e) {
		return e.encode() + "|" + (e.direct ? "direct"
				: e.local ? "local" : "tor");
	}

	private static String describe(List<ElectrumEndpoint> list) {
		StringBuilder sb = new StringBuilder("[");
		for (ElectrumEndpoint e : list) {
			if (sb.length() > 1) sb.append(", ");
			sb.append(describe(e));
		}
		return sb.append(']').toString();
	}

	private static final class Opened {
		final String scan;
		final String broadcast;
		final String scanFallbacks;
		final String broadcastFallbacks;

		Opened(BtcWallet w) throws Exception {
			scan = describe((ElectrumEndpoint) field(w, "scanEndpoint"));
			broadcast = describe((ElectrumEndpoint) field(w,
					"broadcastEndpoint"));
			scanFallbacks = describe(endpoints(w, "scanFallbacks"));
			broadcastFallbacks = describe(endpoints(w,
					"broadcastFallbacks"));
		}

		@SuppressWarnings("unchecked")
		private static List<ElectrumEndpoint> endpoints(BtcWallet w,
				String name) throws Exception {
			return (List<ElectrumEndpoint>) field(w, name);
		}
	}

	private static Executor walletExecutor() throws Exception {
		Field f = VaultViewModel.class.getDeclaredField("WALLET_EXECUTOR");
		f.setAccessible(true);
		return (Executor) f.get(null);
	}

	private Opened openWithoutNetwork(VaultViewModelRig rig,
			VaultViewModel vm, String id) throws Exception {
		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		walletExecutor().execute(() -> {
			blocked.countDown();
			try {
				release.await(120, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blocked.await(60, TimeUnit.SECONDS));
		try {
			Object before = vm.getWalletOpened().getValue();
			vm.openBtcWallet(id, null);
			await(() -> vm.getWalletOpened().getValue() != before);
			Opened o = new Opened((BtcWallet) field(vm, "openBtc"));
			vm.closeBtcWallet();
			return o;
		} finally {
			release.countDown();
			settle();
		}
	}

	private String prepare(VaultViewModelRig rig, VaultViewModel vm)
			throws Exception {
		rig.unlockWalletSection(vm, "section-pass");
		return rig.importWallet(vm, "known", ABANDON);
	}

	@Test
	public void torRoutingUsesTheDefaultOnionWithTheTlsFallback()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		Opened o = openWithoutNetwork(rig, vm, id);
		assertEquals(VIA_TOR_DEFAULT, o.scan);
		assertEquals(VIA_TOR_DEFAULT, o.broadcast);
		assertEquals("[" + VIA_TOR_TLS_FALLBACK + "]", o.scanFallbacks);
		assertEquals("[" + VIA_TOR_TLS_FALLBACK + "]",
				o.broadcastFallbacks);
	}

	@Test
	public void torRoutingBroadcastsThroughAnotherUserNode()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.addNode(USER_ONION + ":50001");
		settle();
		Opened o = openWithoutNetwork(rig, vm, id);
		assertEquals(VIA_TOR_DEFAULT, o.scan);
		assertEquals("onion|0|" + USER_ONION + ":50001||tor", o.broadcast);
		assertEquals("[" + VIA_TOR_TLS_FALLBACK + "]", o.scanFallbacks);
	}

	@Test
	public void torRoutingOnASelectedUserNodeHasNoFallback()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.addNode(USER_ONION + ":50001");
		vm.selectNode(USER_ONION + ":50001");
		settle();
		Opened o = openWithoutNetwork(rig, vm, id);
		String user = "onion|0|" + USER_ONION + ":50001||tor";
		assertEquals(user, o.scan);
		assertEquals(user, o.broadcast);
		assertEquals("[]", o.scanFallbacks);
		assertEquals("[]", o.broadcastFallbacks);
	}

	@Test
	public void directRoutingUsesTheTlsDefaultWithoutTor() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.addNode(USER_ONION + ":50001");
		vm.selectNode(USER_ONION + ":50001");
		vm.setWalletRouting(id, VaultViewModel.ROUTING_DIRECT);
		settle();
		Opened o = openWithoutNetwork(rig, vm, id);
		String direct = "tls|0|electrum.blockstream.info:50002||direct";
		assertEquals(direct, o.scan);
		assertEquals(direct, o.broadcast);
		assertEquals("[]", o.scanFallbacks);
		assertEquals("[]", o.broadcastFallbacks);
	}

	@Test
	public void localRoutingStaysOnTheSelectedLanNode() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.addNode("127.0.0.1:50001");
		vm.addNode(USER_ONION + ":50001");
		vm.selectNode("127.0.0.1:50001");
		vm.setWalletRouting(id, VaultViewModel.ROUTING_LOCAL);
		settle();
		Opened o = openWithoutNetwork(rig, vm, id);
		String lan = "plaintext|1|127.0.0.1:50001||local";
		assertEquals(lan, o.scan);
		assertEquals(lan, o.broadcast);
		assertEquals("[]", o.scanFallbacks);
	}

	@Test
	public void pinsApplyToTlsNodesAndTheFallback() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.confirmNodePin("electrum.blockstream.info:50002", "ABCD12");
		vm.confirmNodePin(ONION + ":50001", "ffff");
		settle();
		Opened o = openWithoutNetwork(rig, vm, id);
		assertEquals(VIA_TOR_DEFAULT, o.scan);
		assertEquals("[tls|0|electrum.blockstream.info:50002|abcd12|tor]",
				o.scanFallbacks);

		vm.addNode("10.0.0.5:50002");
		vm.confirmNodePin("10.0.0.5:50002", "beef");
		vm.selectNode("10.0.0.5:50002");
		vm.setWalletRouting(id, VaultViewModel.ROUTING_LOCAL);
		settle();
		Opened lan = openWithoutNetwork(rig, vm, id);
		assertEquals("tls|1|10.0.0.5:50002|beef|local", lan.scan);
	}

	@Test
	public void oddNodeStringsFallBackToTheDefaultHostAndPort()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		JSONObject s = rig.settings();
		s.put("node", "no-port-here");
		s.put("nodes", new JSONArray());
		rig.writeSettings(s);
		Opened o = openWithoutNetwork(rig, vm, id);
		assertEquals(VIA_TOR_TLS_FALLBACK, o.scan);
		assertEquals(VIA_TOR_DEFAULT, o.broadcast);
		assertEquals("[]", o.scanFallbacks);

		s.put("node", USER_ONION + ":abc");
		rig.writeSettings(s);
		Opened badPort = openWithoutNetwork(rig, vm, id);
		assertEquals("onion|0|" + USER_ONION + ":50002||tor", badPort.scan);

		s.put("node", "electrum.blockstream.info:50001");
		rig.writeSettings(s);
		Opened legacy = openWithoutNetwork(rig, vm, id);
		assertEquals(VIA_TOR_DEFAULT, legacy.scan);
	}

	@Test
	public void selectingANodeRetargetsTheOpenWallet() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String id = prepare(rig, vm);
		vm.addNode(USER_ONION + ":50001");
		settle();
		rig.open(vm, id);
		vm.selectNode(USER_ONION + ":50001");
		settle();
		Opened o = new Opened((BtcWallet) field(vm, "openBtc"));
		String user = "onion|0|" + USER_ONION + ":50001||tor";
		assertEquals(user, o.scan);
		assertEquals(user, o.broadcast);
		assertEquals("[]", o.scanFallbacks);
		vm.closeBtcWallet();
	}
}
