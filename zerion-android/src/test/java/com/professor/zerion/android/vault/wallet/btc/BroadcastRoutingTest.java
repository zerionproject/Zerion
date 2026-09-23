package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * BTC-06: a user who selected a node of their own, or local or direct
 * routing, broadcasts through that node; the scan/broadcast split across
 * shipped servers applies only while the shipped default is in use.
 */
public class BroadcastRoutingTest {

	private static final String DEFAULT = "default.onion:50001";
	private static final String MINE = "mynode.local:50002";
	private static final String OTHER = "other.onion:50001";

	@Test
	public void ownNodeBroadcastsThroughItself() {
		assertEquals(MINE, BroadcastRouting.chooseBroadcastNode(MINE,
				DEFAULT, Arrays.asList(MINE, OTHER), "tor"));
	}

	@Test
	public void localAndDirectRoutingNeverLeaveTheSelectedNode() {
		assertEquals(DEFAULT, BroadcastRouting.chooseBroadcastNode(DEFAULT,
				DEFAULT, Arrays.asList(OTHER), "local"));
		assertEquals(MINE, BroadcastRouting.chooseBroadcastNode(MINE,
				DEFAULT, Collections.emptyList(), "direct"));
	}

	@Test
	public void shippedDefaultKeepsTheSplitWhenAnotherNodeExists() {
		assertEquals(OTHER, BroadcastRouting.chooseBroadcastNode(DEFAULT,
				DEFAULT, Arrays.asList(OTHER), "tor"));
		assertEquals(DEFAULT, BroadcastRouting.chooseBroadcastNode(DEFAULT,
				DEFAULT, Collections.emptyList(), "tor"));
	}
}
