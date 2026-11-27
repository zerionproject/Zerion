package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FeeCheckRoutingTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String USER =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion:50001";

	private final BtcWalletSettings settings;
	private final BtcNodeRouting routing;

	public FeeCheckRoutingTest() throws Exception {
		settings = new BtcWalletSettings(
				new WalletStore(new InMemoryVault().manager));
		routing = new BtcNodeRouting(settings);
	}

	private static String spec(ElectrumEndpoint e) {
		return e.host + ":" + e.port;
	}

	@Test
	public void defaultConfigCrossChecksAgainstTheOtherShippedNode() {
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		ElectrumEndpoint bcast = routing.broadcastEndpoint("w", scan);
		assertEquals(spec(scan), spec(bcast));
		ElectrumEndpoint check = routing.feeCheckEndpoint("w", scan, bcast);
		assertNotNull("the shipped default has a fee cross-check", check);
		assertEquals(BtcNodeRouting.DEFAULT_NODE, spec(check));
		assertTrue(check.viaTor());
	}

	@Test
	public void defaultConfigWalletAsksTheShippedAlternate()
			throws IOException {
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		ElectrumEndpoint bcast = routing.broadcastEndpoint("w", scan);
		FakeElectrum primary = new FakeElectrum();
		FakeElectrum alternate = new FakeElectrum();
		primary.feeBtcPerKb = 0.002;
		alternate.feeBtcPerKb = 0.0001;
		List<String> asked = new ArrayList<>();
		ElectrumRpc.Factory f = (ep, port, tag) -> {
			asked.add(spec(ep) + "|" + tag);
			return spec(ep).equals(spec(scan)) ? primary : alternate;
		};
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, scan,
				bcast, "w", f, (url, tag) -> null);
		w.setFeeCheckEndpoint(routing.feeCheckEndpoint("w", scan, bcast));
		assertEquals(10.0, w.feeOptions()[1], 1e-9);
		assertTrue(asked.contains(BtcNodeRouting.DEFAULT_NODE + "|"
				+ TorIsolation.feeCheck("w")));
	}

	@Test
	public void ownNodeIsNotCrossCheckedWithAThirdParty() {
		routing.addNode(USER);
		routing.selectNode(USER);
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		ElectrumEndpoint bcast = routing.broadcastEndpoint("w", scan);
		assertNull(routing.feeCheckEndpoint("w", scan, bcast));
	}

	@Test
	public void directAndLocalRoutingHaveNoCrossCheck() {
		for (String mode : new String[]{BtcNodeRouting.ROUTING_DIRECT,
				BtcNodeRouting.ROUTING_LOCAL}) {
			routing.setRouting("w", mode);
			ElectrumEndpoint scan = routing.scanEndpoint("w");
			ElectrumEndpoint bcast = routing.broadcastEndpoint("w", scan);
			assertNull(mode, routing.feeCheckEndpoint("w", scan, bcast));
		}
	}

	@Test
	public void aSeparateBroadcastServerIsTheSecondOpinion() {
		ElectrumEndpoint scan = routing.endpointFor(USER);
		ElectrumEndpoint bcast = routing.endpointFor(
				BtcNodeRouting.DEFAULT_ONION_NODE);
		assertEquals(BtcNodeRouting.DEFAULT_ONION_NODE,
				spec(routing.feeCheckEndpoint("w", scan, bcast)));
	}

	@Test
	public void shippedNodesPointAtEachOther() {
		assertEquals(BtcNodeRouting.DEFAULT_NODE, BtcNodeRouting
				.alternateShippedNode(BtcNodeRouting.DEFAULT_ONION_NODE));
		assertEquals(BtcNodeRouting.DEFAULT_ONION_NODE, BtcNodeRouting
				.alternateShippedNode(BtcNodeRouting.DEFAULT_NODE));
		assertNull(BtcNodeRouting.alternateShippedNode(USER));
	}
}
