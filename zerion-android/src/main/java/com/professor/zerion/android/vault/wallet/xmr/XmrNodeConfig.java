package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@NotNullByDefault
public final class XmrNodeConfig {

	public enum Mode { OWN, VETTED, CUSTOM, DIRECT }

	public final Mode mode;
	public final String ownNode;
	public final boolean ownTrusted;
	public final List<String> customNodes;
	public final String directNode;
	public final List<String> unverifiedAcks;
	public final boolean stopWhenPaused;
	public final boolean pausePrompted;

	public XmrNodeConfig(Mode mode, String ownNode, List<String> customNodes,
			String directNode) {
		this(mode, ownNode, false, customNodes, directNode);
	}

	public XmrNodeConfig(Mode mode, String ownNode, boolean ownTrusted,
			List<String> customNodes, String directNode) {
		this(mode, ownNode, ownTrusted, customNodes, directNode,
				new ArrayList<>());
	}

	public XmrNodeConfig(Mode mode, String ownNode, boolean ownTrusted,
			List<String> customNodes, String directNode,
			List<String> unverifiedAcks) {
		this(mode, ownNode, ownTrusted, customNodes, directNode,
				unverifiedAcks, false, false);
	}

	public XmrNodeConfig(Mode mode, String ownNode, boolean ownTrusted,
			List<String> customNodes, String directNode,
			List<String> unverifiedAcks, boolean stopWhenPaused,
			boolean pausePrompted) {
		this.mode = mode;
		this.ownNode = ownNode;
		this.ownTrusted = ownTrusted;
		this.customNodes = customNodes;
		this.directNode = directNode;
		this.unverifiedAcks = unverifiedAcks;
		this.stopWhenPaused = stopWhenPaused;
		this.pausePrompted = pausePrompted;
	}

	public XmrNodeConfig withPauseChoice(boolean stop) {
		return new XmrNodeConfig(mode, ownNode, ownTrusted, customNodes,
				directNode, unverifiedAcks, stop, true);
	}

	public XmrNodeConfig withAcknowledged(List<String> acks) {
		List<String> all = new ArrayList<>(unverifiedAcks);
		for (String a : acks) if (!all.contains(a)) all.add(a);
		return new XmrNodeConfig(mode, ownNode, ownTrusted, customNodes,
				directNode, all, false, true);
	}

	@Nullable
	public XmrNode pausedNode() {
		try {
			if (mode == Mode.OWN && !ownNode.isEmpty()) {
				XmrNode own = XmrNode.parse(ownNode, XmrNode.Source.USER_OWNED,
						ownTrusted);
				return isAllowed(own) ? null : own;
			}
			if (mode == Mode.CUSTOM) {
				XmrNode first = null;
				for (String c : customNodes) {
					XmrNode n = XmrNode.parse(c, XmrNode.Source.CUSTOM, false);
					if (isAllowed(n)) return null;
					if (first == null) first = n;
				}
				return first;
			}
		} catch (RuntimeException malformed) {
		}
		return null;
	}

	public boolean isPaused() {
		return pausedNode() != null;
	}

	public boolean isAllowed(XmrNode node) {
		return !node.isUnverifiedOverTor()
				|| unverifiedAcks.contains(node.endpointId());
	}

	public static XmrNodeConfig vettedDefault() {
		return new XmrNodeConfig(Mode.VETTED, "", new ArrayList<>(), "");
	}

	public List<XmrNode> toFailoverList() {
		if (stopWhenPaused && isPaused()) {
			return new ArrayList<>();
		}
		try {
			switch (mode) {
				case OWN:
					if (!ownNode.isEmpty()) {
						XmrNode own = XmrNode.parse(ownNode,
								XmrNode.Source.USER_OWNED, ownTrusted);
						if (!isAllowed(own)) break;
						return XmrNodeSelector.failoverOrder(own,
								new ArrayList<>(), false, null);
					}
					break;
				case CUSTOM:
					List<XmrNode> customs = new ArrayList<>();
					for (String c : customNodes) {
						XmrNode n = XmrNode.parse(c, XmrNode.Source.CUSTOM, false);
						if (isAllowed(n)) customs.add(n);
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
				List<String> acks = new ArrayList<>();
				JSONArray ackArr = n.optJSONArray("unverifiedAcks");
				if (ackArr != null) {
					for (int i = 0; i < ackArr.length(); i++) {
						String a = ackArr.optString(i, "");
						if (!a.isEmpty()) acks.add(a);
					}
				}
				return new XmrNodeConfig(mode, n.optString("own", ""),
						n.optBoolean("ownTrusted", false), custom,
						n.optString("direct", ""), acks,
						n.optBoolean("stopWhenPaused", false),
						n.optBoolean("pausePrompted", false));
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
			n.put("ownTrusted", ownTrusted);
			n.put("direct", directNode);
			JSONArray arr = new JSONArray();
			for (String c : customNodes) arr.put(c);
			n.put("custom", arr);
			JSONArray ackArr = new JSONArray();
			for (String a : unverifiedAcks) ackArr.put(a);
			n.put("unverifiedAcks", ackArr);
			n.put("stopWhenPaused", stopWhenPaused);
			n.put("pausePrompted", pausePrompted);
			xmr.put("_nodes", n);
			root.put("xmr", xmr);
			store.writeSettings(root.toString());
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
