package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class XmrNodeConfigTest {

	private static final String ONION =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";

	@Test
	public void vettedResolvesToTheVettedTorSet() {
		List<XmrNode> order = XmrNodeConfig.vettedDefault().toFailoverList();
		assertEquals(XmrNodeSelector.VETTED_DEFAULT.size(), order.size());
		for (XmrNode n : order) {
			assertTrue(n.isOnion());
			assertTrue(n.usesTor());
			assertFalse("a public node is never trusted", n.trusted);
		}
	}

	@Test
	public void ownNodeIsUsedExclusivelyNoSilentPublicDowngrade() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN, ONION,
				new ArrayList<>(), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals("own node only, no vetted fallback", 1, order.size());
		assertEquals(XmrNode.Source.USER_OWNED, order.get(0).source);
		assertTrue(order.get(0).usesTor());
	}

	@Test
	public void directModeIsExclusiveAndClearnet() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.DIRECT, "",
				new ArrayList<>(), "203.0.113.10:18081");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals("direct is exclusive", 1, order.size());
		assertEquals(XmrNode.Source.DIRECT, order.get(0).source);
		assertFalse("direct does not use Tor", order.get(0).usesTor());
	}

	@Test
	public void customNodesAreUsedOverTor() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.CUSTOM, "",
				Arrays.asList(ONION), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertEquals(1, order.size());
		assertEquals(XmrNode.Source.CUSTOM, order.get(0).source);
		assertTrue(order.get(0).usesTor());
	}

	@Test
	public void malformedNodeFallsBackToVettedNeverEmpty() {
		XmrNodeConfig cfg = new XmrNodeConfig(XmrNodeConfig.Mode.OWN,
				"not a node", new ArrayList<>(), "");
		List<XmrNode> order = cfg.toFailoverList();
		assertFalse("a malformed own node falls back to a safe set, never empty",
				order.isEmpty());
		for (XmrNode n : order) assertTrue(n.usesTor());
	}
}
