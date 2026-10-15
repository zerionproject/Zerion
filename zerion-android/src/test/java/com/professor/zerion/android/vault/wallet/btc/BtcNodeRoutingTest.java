package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.InMemoryVault;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BtcNodeRoutingTest {

	private static final String USER =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion:50001";

	private final BtcWalletSettings settings;
	private final BtcNodeRouting routing;

	public BtcNodeRoutingTest() throws Exception {
		settings = new BtcWalletSettings(
				new WalletStore(new InMemoryVault().manager));
		routing = new BtcNodeRouting(settings);
	}

	private static String spec(ElectrumEndpoint e) {
		return e.host + ":" + e.port;
	}

	@Test
	public void theOnionNodeIsTheDefault() {
		assertEquals(BtcNodeRouting.DEFAULT_ONION_NODE,
				BtcNodeRouting.preferredDefaultNode());
		assertEquals("electrum.blockstream.info:50002",
				BtcNodeRouting.DEFAULT_NODE);
	}

	@Test
	public void torRoutingNeverLeavesTor() throws Exception {
		settings.write(new JSONObject());
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		ElectrumEndpoint broadcast = routing.broadcastEndpoint("w", scan);
		List<ElectrumEndpoint> fallbacks = routing.fallbacks("w", scan);
		assertEquals(BtcNodeRouting.DEFAULT_ONION_NODE, spec(scan));
		assertSame(scan, broadcast);
		assertEquals(1, fallbacks.size());
		assertEquals(BtcNodeRouting.DEFAULT_NODE, spec(fallbacks.get(0)));
		for (ElectrumEndpoint e : Arrays.asList(scan, broadcast,
				fallbacks.get(0))) {
			assertTrue(spec(e), e.viaTor());
		}
	}

	@Test
	public void withoutADocumentTheBroadcastNodeIsTheScanNode() {
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		assertSame(scan, routing.broadcastEndpoint("w", scan));
	}

	@Test
	public void torRoutingSplitsBroadcastOntoAUserNode() throws Exception {
		routing.addNode(USER);
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		ElectrumEndpoint broadcast = routing.broadcastEndpoint("w", scan);
		assertEquals(BtcNodeRouting.DEFAULT_ONION_NODE, spec(scan));
		assertEquals(USER, spec(broadcast));
		assertTrue(broadcast.viaTor());
	}

	@Test
	public void directRoutingIsTlsWithoutTorAndWithoutFallbacks() {
		routing.addNode(USER);
		routing.selectNode(USER);
		routing.setRouting("w", BtcNodeRouting.ROUTING_DIRECT);
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		assertEquals(BtcNodeRouting.DEFAULT_NODE, spec(scan));
		assertTrue(scan.direct);
		assertTrue(scan.tls());
		assertFalse(scan.viaTor());
		assertSame(scan, routing.broadcastEndpoint("w", scan));
		assertTrue(routing.fallbacks("w", scan).isEmpty());
		assertEquals(BtcNodeRouting.ROUTING_TOR, routing.routing("other"));
	}

	@Test
	public void localRoutingStaysOnTheLan() {
		routing.addNode("192.168.1.9:50001");
		routing.selectNode("192.168.1.9:50001");
		routing.setRouting("w", BtcNodeRouting.ROUTING_LOCAL);
		ElectrumEndpoint scan = routing.scanEndpoint("w");
		assertTrue(scan.local);
		assertFalse(scan.viaTor());
		assertSame(scan, routing.broadcastEndpoint("w", scan));
		assertTrue(routing.fallbacks("w", scan).isEmpty());
	}

	@Test
	public void unknownRoutingModesMeanTor() {
		routing.setRouting("w", "clearnet");
		assertEquals(BtcNodeRouting.ROUTING_TOR, routing.routing("w"));
		assertTrue(routing.scanEndpoint("w").viaTor());
	}

	@Test
	public void pinsAttachToTlsEndpointsOnly() {
		routing.savePin("10.0.0.5:50002", "BEEF");
		routing.savePin(USER, "cafe");
		assertEquals("beef", routing.endpointFor("10.0.0.5:50002").pinSha256);
		assertEquals(null, routing.endpointFor(USER).pinSha256);
		assertEquals(50002, routing.endpointFor("host.onion:x").port);
	}

	@Test
	public void nodeListNormalisesTheSelection() throws Exception {
		BtcNodeRouting.NodeList empty = routing.nodeList();
		assertEquals(Collections.singletonList(
				BtcNodeRouting.preferredDefaultNode()), empty.nodes);
		JSONObject o = new JSONObject();
		o.put("nodes", new JSONArray().put(USER).put(USER));
		o.put("node", "electrum.blockstream.info:50001");
		settings.write(o);
		BtcNodeRouting.NodeList list = routing.nodeList();
		assertEquals(Arrays.asList(BtcNodeRouting.preferredDefaultNode(),
				USER), list.nodes);
		assertEquals(BtcNodeRouting.preferredDefaultNode(), list.selected);
		routing.selectNode(USER);
		assertEquals(USER, routing.nodeList().selected);
		routing.deleteNode(USER);
		assertEquals(BtcNodeRouting.preferredDefaultNode(),
				routing.nodeList().selected);
		assertEquals(0, settings.read().getJSONArray("nodes").length());
	}
}
