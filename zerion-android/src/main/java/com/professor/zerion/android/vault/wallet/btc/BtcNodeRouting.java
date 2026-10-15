package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class BtcNodeRouting {

	public static final class NodeList {

		public final List<String> nodes;
		public final String selected;

		NodeList(List<String> nodes, String selected) {
			this.nodes = nodes;
			this.selected = selected;
		}
	}

	private static final String ELECTRUM_HOST = "electrum.blockstream.info";
	private static final int ELECTRUM_PORT = 50002;

	public static final String DEFAULT_NODE =
			ELECTRUM_HOST + ":" + ELECTRUM_PORT;
	private static final String LEGACY_DEFAULT_NODE =
			ELECTRUM_HOST + ":50001";

	public static final String DEFAULT_ONION_NODE =
			"kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion"
					+ ":50001";

	public static final String DEFAULT_ONION_NODE_2 = "";

	public static final String ROUTING_TOR = "tor";
	public static final String ROUTING_DIRECT = "direct";
	public static final String ROUTING_LOCAL = "local";

	private final BtcWalletSettings settings;

	public BtcNodeRouting(BtcWalletSettings settings) {
		this.settings = settings;
	}

	public static String preferredDefaultNode() {
		return ElectrumEndpoint.preferredDefaultSpec(DEFAULT_ONION_NODE,
				DEFAULT_NODE);
	}

	private static String normalizeNode(String node) {
		return LEGACY_DEFAULT_NODE.equals(node) ? preferredDefaultNode() : node;
	}

	@Nullable
	private String pinFor(String node) {
		try {
			JSONObject pins = settings.read().optJSONObject("pins");
			if (pins != null) {
				String p = pins.optString(node, "");
				return p.isEmpty() ? null : p;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	public void savePin(String node, String pin) {
		try {
			synchronized (settings.lock()) {
				JSONObject o = settings.read();
				JSONObject pins = o.optJSONObject("pins");
				if (pins == null) {
					pins = new JSONObject();
				}
				pins.put(node, pin);
				o.put("pins", pins);
				settings.write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public NodeList nodeList() {
		List<String> list = new ArrayList<>();
		list.add(preferredDefaultNode());
		String selected = preferredDefaultNode();
		try {
			JSONObject o = settings.readIfPresent();
			if (o != null) {
				JSONArray arr = o.optJSONArray("nodes");
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						String n = arr.getString(i);
						if (!list.contains(n)) {
							list.add(n);
						}
					}
				}
				selected = normalizeNode(o.optString("node",
						preferredDefaultNode()));
			}
		} catch (Throwable ignored) {
		}
		if (!list.contains(selected)) {
			selected = preferredDefaultNode();
		}
		return new NodeList(list, selected);
	}

	public void addNode(String node) {
		try {
			synchronized (settings.lock()) {
				JSONObject o = settings.read();
				JSONArray arr = o.optJSONArray("nodes");
				if (arr == null) {
					arr = new JSONArray();
				}
				boolean exists = node.equals(preferredDefaultNode());
				for (int i = 0; i < arr.length(); i++) {
					if (arr.getString(i).equals(node)) {
						exists = true;
					}
				}
				if (!exists) {
					arr.put(node);
				}
				o.put("nodes", arr);
				settings.write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public void selectNode(String node) {
		try {
			synchronized (settings.lock()) {
				JSONObject o = settings.read();
				o.put("node", node);
				settings.write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	public void deleteNode(String node) {
		try {
			synchronized (settings.lock()) {
				JSONObject o = settings.read();
				JSONArray arr = o.optJSONArray("nodes");
				JSONArray keep = new JSONArray();
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						String n = arr.getString(i);
						if (!n.equals(node)) {
							keep.put(n);
						}
					}
				}
				o.put("nodes", keep);
				if (node.equals(o.optString("node", preferredDefaultNode()))) {
					o.put("node", preferredDefaultNode());
				}
				settings.write(o);
			}
		} catch (Throwable ignored) {
		}
	}

	private String[] selectedNodeHostPort() {
		String node = preferredDefaultNode();
		try {
			JSONObject o = settings.readIfPresent();
			if (o != null) {
				node = o.optString("node", preferredDefaultNode());
			}
		} catch (Throwable ignored) {
		}
		node = normalizeNode(node);
		int i = node.lastIndexOf(':');
		if (i <= 0) {
			return new String[]{ELECTRUM_HOST, String.valueOf(ELECTRUM_PORT)};
		}
		return new String[]{node.substring(0, i), node.substring(i + 1)};
	}

	public ElectrumEndpoint endpointFor(String node) {
		int i = node.lastIndexOf(':');
		String host = i <= 0 ? ELECTRUM_HOST : node.substring(0, i);
		int port = ELECTRUM_PORT;
		try {
			port = Integer.parseInt(node.substring(i + 1).trim());
		} catch (Exception ignored) {
		}
		return ElectrumEndpoint.fromUserInput(host, port, pinFor(node));
	}

	private ElectrumEndpoint selectedScanEndpoint() {
		String[] hp = selectedNodeHostPort();
		return endpointFor(hp[0] + ":" + hp[1]);
	}

	private ElectrumEndpoint broadcastEndpointFor(ElectrumEndpoint scan,
			String routing) {
		try {
			JSONObject o = settings.readIfPresent();
			if (o != null) {
				String selected = normalizeNode(
						o.optString("node", preferredDefaultNode()));
				List<String> userNodes = new ArrayList<>();
				JSONArray arr = o.optJSONArray("nodes");
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						userNodes.add(arr.getString(i));
					}
				}
				String chosen = BroadcastRouting.chooseBroadcastNode(selected,
						preferredDefaultNode(), userNodes, routing);
				if (chosen.equals(selected)) return scan;
				return endpointFor(chosen);
			}
		} catch (Throwable ignored) {
		}
		return scan;
	}

	private List<ElectrumEndpoint> fallbackChainFor(ElectrumEndpoint scanEp) {
		List<ElectrumEndpoint> fbs = new ArrayList<>();
		String scanSpec = scanEp.host + ":" + scanEp.port;
		if (scanSpec.equals(DEFAULT_ONION_NODE)
				&& !DEFAULT_ONION_NODE.isEmpty()) {
			if (!DEFAULT_ONION_NODE_2.isEmpty()) {
				fbs.add(endpointFor(DEFAULT_ONION_NODE_2));
			}
			fbs.add(endpointFor(DEFAULT_NODE));
		}
		return fbs;
	}

	public String routing(String walletId) {
		String r = settings.privacy(walletId).optString("routing",
				ROUTING_TOR);
		if (ROUTING_DIRECT.equals(r) || ROUTING_LOCAL.equals(r)) {
			return r;
		}
		return ROUTING_TOR;
	}

	public void setRouting(String walletId, String mode) {
		settings.mutatePrivacy(walletId, w -> w.put("routing", mode));
	}

	public ElectrumEndpoint scanEndpoint(String walletId) {
		if (ROUTING_DIRECT.equals(routing(walletId))) {
			return endpointFor(DEFAULT_NODE).asDirect();
		}
		return selectedScanEndpoint();
	}

	public ElectrumEndpoint broadcastEndpoint(String walletId,
			ElectrumEndpoint scanEp) {
		String routing = routing(walletId);
		if (ROUTING_DIRECT.equals(routing)) {
			return scanEp;
		}
		return broadcastEndpointFor(scanEp, routing);
	}

	public List<ElectrumEndpoint> fallbacks(String walletId,
			ElectrumEndpoint scanEp) {
		String routing = routing(walletId);
		if (ROUTING_DIRECT.equals(routing) || ROUTING_LOCAL.equals(routing)) {
			return new ArrayList<>();
		}
		return fallbackChainFor(scanEp);
	}
}
