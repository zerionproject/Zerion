package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * User node preference for XMR sync, four tiers:
 * <ul>
 *   <li>OWN — the user's own node, best privacy. Used exclusively; if it is
 *   unreachable the wallet goes offline rather than silently downgrading to a
 *   public node.</li>
 *   <li>VETTED — the Zerion-vetted Tor node set, the default. Never "trusted".</li>
 *   <li>CUSTOM — user-added remote node(s) over Tor.</li>
 *   <li>DIRECT — an explicit clearnet node, reduced privacy. Exclusive and only
 *   ever active after the user acknowledges the privacy warning; it is never an
 *   automatic failover from a Tor tier.</li>
 * </ul>
 * Persisted app-wide in the encrypted vault settings at {@code xmr._nodes}.
 */
@NotNullByDefault
public final class XmrNodeConfig {

	public enum Mode { OWN, VETTED, CUSTOM, DIRECT }

	public final Mode mode;
	public final String ownNode;
	public final List<String> customNodes;
	public final String directNode;

	public XmrNodeConfig(Mode mode, String ownNode, List<String> customNodes,
			String directNode) {
		this.mode = mode;
		this.ownNode = ownNode;
		this.customNodes = customNodes;
		this.directNode = directNode;
	}

	public static XmrNodeConfig vettedDefault() {
		return new XmrNodeConfig(Mode.VETTED, "", new ArrayList<>(), "");
	}

	/** The sequential failover order this config resolves to. */
	public List<XmrNode> toFailoverList() {
		try {
			switch (mode) {
				case OWN:
					if (!ownNode.isEmpty()) {
						XmrNode own = XmrNode.parse(ownNode,
								XmrNode.Source.USER_OWNED, true);
						return XmrNodeSelector.failoverOrder(own,
								new ArrayList<>(), false, null);
					}
					break;
				case CUSTOM:
					List<XmrNode> customs = new ArrayList<>();
					for (String c : customNodes) {
						customs.add(XmrNode.parse(c, XmrNode.Source.CUSTOM, false));
					}
					return XmrNodeSelector.failoverOrder(null, customs,
							customs.isEmpty(), null);
				case DIRECT:
					if (!directNode.isEmpty()) {
						XmrNode direct = XmrNode.parse(directNode,
								XmrNode.Source.DIRECT, false);
						return XmrNodeSelector.failoverOrder(null,
								new ArrayList<>(), false, direct);
					}
					break;
				default:
					break;
			}
		} catch (RuntimeException malformed) {
		}
		return XmrNodeSelector.failoverOrder(null, new ArrayList<>(), true, null);
	}

	public static XmrNodeConfig load(XmrStore store) {
		try {
			synchronized (store.settingsMonitor()) {
				String json = store.readSettings();
				if (json == null) return vettedDefault();
				JSONObject xmr = new JSONObject(json).optJSONObject("xmr");
				if (xmr == null) return vettedDefault();
				JSONObject n = xmr.optJSONObject("_nodes");
				if (n == null) return vettedDefault();
				Mode mode = parseMode(n.optString("mode", "VETTED"));
				List<String> custom = new ArrayList<>();
				JSONArray arr = n.optJSONArray("custom");
				if (arr != null) {
					for (int i = 0; i < arr.length(); i++) {
						custom.add(arr.optString(i, ""));
					}
				}
				return new XmrNodeConfig(mode, n.optString("own", ""),
						custom, n.optString("direct", ""));
			}
		} catch (Exception e) {
			return vettedDefault();
		}
	}

	public void save(XmrStore store) throws Exception {
		synchronized (store.settingsMonitor()) {
			String json = store.readSettings();
			JSONObject root = json == null ? new JSONObject()
					: new JSONObject(json);
			JSONObject xmr = root.optJSONObject("xmr");
			if (xmr == null) xmr = new JSONObject();
			JSONObject n = new JSONObject();
			n.put("mode", mode.name());
			n.put("own", ownNode);
			n.put("direct", directNode);
			JSONArray arr = new JSONArray();
			for (String c : customNodes) arr.put(c);
			n.put("custom", arr);
			xmr.put("_nodes", n);
			root.put("xmr", xmr);
			store.writeSettings(root.toString());
		}
	}

	@Nullable
	public String activeNodeLabel() {
		List<XmrNode> order = toFailoverList();
		if (order.isEmpty()) return null;
		XmrNode first = order.get(0);
		switch (mode) {
			case OWN:
				return "Own node · " + first.shortLabel();
			case CUSTOM:
				return "Custom · " + first.shortLabel();
			case DIRECT:
				return "Direct (clearnet) · " + first.shortLabel();
			default:
				return "Vetted Tor nodes";
		}
	}

	private static Mode parseMode(String s) {
		try {
			return Mode.valueOf(s);
		} catch (IllegalArgumentException e) {
			return Mode.VETTED;
		}
	}
}
