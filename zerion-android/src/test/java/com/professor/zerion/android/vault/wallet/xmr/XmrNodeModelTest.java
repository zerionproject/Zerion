package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class XmrNodeModelTest {

	private static final String ONION =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion";

	@Test
	public void rejectsInvalidHostsAndPorts() {
		String[] bad = {"bad host!:18089", "node.example.com", ":18089",
				"1.2.3.4:0", "1.2.3.4:70000", "1.2.3.4:notaport", ""};
		for (String spec : bad) {
			try {
				XmrNode.parse(spec, XmrNode.Source.CUSTOM, false);
				fail("must reject: " + spec);
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void acceptsOnionAndIpLiterals() {
		assertTrue(XmrNode.parse(ONION + ":18089",
				XmrNode.Source.VETTED, false).isOnion());
		assertFalse(XmrNode.parse("192.168.1.5:18081",
				XmrNode.Source.CUSTOM, false).isOnion());
		assertFalse(XmrNode.parse("[2001:db8::1]:18081",
				XmrNode.Source.CUSTOM, false).isOnion());
	}

	@Test
	public void onionCannotBeDirect() {
		try {
			XmrNode.parse(ONION + ":18089", XmrNode.Source.DIRECT, false);
			fail("onion is never a Direct node");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void onlyUserOwnedMayBeTrusted() {
		XmrNode.parse(ONION + ":18089", XmrNode.Source.USER_OWNED, true);
		for (XmrNode.Source s : new XmrNode.Source[]{XmrNode.Source.VETTED,
				XmrNode.Source.CUSTOM}) {
			try {
				XmrNode.parse(ONION + ":18089", s, true);
				fail("non-user node cannot be trusted: " + s);
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void failoverOrderIsUserThenVettedThenCustom() {
		XmrNode user = XmrNode.parse(ONION + ":18089",
				XmrNode.Source.USER_OWNED, true);
		XmrNode custom = XmrNode.parse("10.0.0.9:18081",
				XmrNode.Source.CUSTOM, false);
		List<XmrNode> order = XmrNodeSelector.failoverOrder(user,
				Arrays.asList(custom), true, null);
		assertEquals(user, order.get(0));
		assertEquals(XmrNode.Source.VETTED, order.get(1).source);
		assertEquals(custom, order.get(order.size() - 1));
		assertTrue(XmrNodeSelector.allOverTorExceptDirect(order));
	}

	@Test
	public void directModeIsExclusiveNeverAFallback() {
		List<XmrNode> noDirect = XmrNodeSelector.failoverOrder(null,
				new ArrayList<>(), true, null);
		for (XmrNode n : noDirect) {
			assertTrue("no clearnet node without opt-in", n.usesTor());
		}
		XmrNode user = XmrNode.parse(ONION + ":18089",
				XmrNode.Source.USER_OWNED, true);
		XmrNode direct = XmrNode.parse("203.0.113.7:18081",
				XmrNode.Source.DIRECT, false);
		List<XmrNode> withDirect = XmrNodeSelector.failoverOrder(user,
				new ArrayList<>(), true, direct);
		assertEquals("Direct mode uses only the Direct node", 1,
				withDirect.size());
		assertEquals(direct, withDirect.get(0));
		assertFalse(withDirect.get(0).usesTor());
	}

	@Test
	public void vettedDefaultIsFourStableOnions() {
		assertEquals(4, XmrNodeSelector.VETTED_DEFAULT.size());
		for (XmrNode n : XmrNodeSelector.VETTED_DEFAULT) {
			assertTrue(n.isOnion());
			assertFalse("vetted nodes are not trusted", n.trusted);
			assertEquals(18089, n.port);
		}
	}

	@Test
	public void dedupKeepsHighestTierOccurrence() {
		XmrNode user = XmrNode.parse(
				XmrNodeSelector.VETTED_DEFAULT.get(0).host + ":18089",
				XmrNode.Source.USER_OWNED, true);
		List<XmrNode> order = XmrNodeSelector.failoverOrder(user,
				new ArrayList<>(), true, null);
		int count = 0;
		for (XmrNode n : order) {
			if (n.host.equals(user.host)) count++;
		}
		assertEquals("duplicate host appears once", 1, count);
		assertEquals(user, order.get(0));
	}

	@Test
	public void hostnamesAllowedButLocalDnsOnlyForDirect() {
		XmrNode torHost = XmrNode.parse("node.example.com:18089",
				XmrNode.Source.CUSTOM, false);
		assertEquals(XmrNode.HostType.HOSTNAME, torHost.hostType);
		assertTrue("custom-over-Tor resolves remotely", torHost.usesTor());
		assertFalse("no local DNS for a Tor node", torHost.requiresLocalDns());

		XmrNode directHost = XmrNode.parse("node.example.com:18081",
				XmrNode.Source.DIRECT, false);
		assertFalse(directHost.usesTor());
		assertTrue("only a Direct hostname needs local DNS",
				directHost.requiresLocalDns());
	}

	@Test
	public void endpointIdCanonicalisesEquivalentSpellings() {
		assertEquals("direct:203.0.113.5:18081",
				XmrNode.parse("203.0.113.5:18081",
						XmrNode.Source.DIRECT, false).endpointId());
		assertEquals("a hostname is case-folded for DNS",
				XmrNode.parse("Node.Example.COM:18081",
						XmrNode.Source.DIRECT, false).endpointId(),
				XmrNode.parse("node.example.com:18081",
						XmrNode.Source.DIRECT, false).endpointId());
		assertEquals("compressed and expanded IPv6 are one identity",
				XmrNode.parse("[2001:db8::1]:18081",
						XmrNode.Source.CUSTOM, false).endpointId(),
				XmrNode.parse("[2001:0db8:0000:0000:0000:0000:0000:0001]:18081",
						XmrNode.Source.CUSTOM, false).endpointId());
		assertEquals("IPv6 stays bracketed in the endpoint",
				"tor:[2001:db8:0:0:0:0:0:1]:18081",
				XmrNode.parse("[2001:db8::1]:18081",
						XmrNode.Source.CUSTOM, false).endpointId());
	}

	@Test
	public void endpointIdTracksTransportNotSourceLabel() {
		String a = XmrNode.parse(ONION + ":18089",
				XmrNode.Source.VETTED, false).endpointId();
		String b = XmrNode.parse(ONION + ":18089",
				XmrNode.Source.CUSTOM, false).endpointId();
		assertEquals("the source label is not part of the identity", a, b);
		assertTrue(a.startsWith("tor:"));
	}
}
