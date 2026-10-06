package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

@NotNullByDefault
public final class BroadcastRouting {

	private BroadcastRouting() {
	}

	public static String chooseBroadcastNode(String selected,
			String defaultNode, List<String> userNodes, String routing) {
		if ("direct".equals(routing) || "local".equals(routing)) {
			return selected;
		}
		if (userNodes.contains(selected)) return selected;
		if (!defaultNode.equals(selected)) return defaultNode;
		for (String n : userNodes) {
			if (!n.equals(selected)) return n;
		}
		return selected;
	}
}
