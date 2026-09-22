package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

/**
 * Which node receives a signed transaction. The wallet scans through one
 * server and broadcasts through another so that no single shipped server
 * links a wallet's balance queries to its spends; that split only makes
 * sense while the user relies on shipped servers. A user who selected a
 * node of their own, or chose local or direct routing, has chosen where
 * their traffic goes, and their transaction is broadcast through that
 * node rather than through the shipped default.
 */
@NotNullByDefault
public final class BroadcastRouting {

	private BroadcastRouting() {
	}

	/**
	 * @param selected the node selected for scanning.
	 * @param defaultNode the shipped default node.
	 * @param userNodes the nodes the user added.
	 * @param routing the wallet's routing mode: tor, local or direct.
	 * @return the node spec to broadcast through.
	 */
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
