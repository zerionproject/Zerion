package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * XMR-04: every Monero daemon stream must carry SOCKS5 credentials that are
 * distinct per wallet and per purpose, in the exact grammar the vendored
 * wallet2 proxy parser accepts, without exposing the raw wallet identity.
 */
public class XmrTorIsolationTest {

	private static final String W1 = "wallet-one-0123456789";
	private static final String W2 = "wallet-two-0123456789";

	/** Mirrors wallet2's net::uri_components split: scheme, userinfo, hostport. */
	private static String[] parse(String uri) {
		int schemeEnd = uri.indexOf("://");
		assertTrue("scheme required", schemeEnd > 0);
		String scheme = uri.substring(0, schemeEnd);
		String rest = uri.substring(schemeEnd + 3);
		int at = rest.lastIndexOf('@');
		assertTrue("userinfo required", at > 0);
		String userinfo = rest.substring(0, at);
		String hostport = rest.substring(at + 1);
		int colon = userinfo.indexOf(':');
		assertTrue("user:pass required", colon > 0);
		return new String[] {scheme, userinfo.substring(0, colon),
				userinfo.substring(colon + 1), hostport};
	}

	@Test
	public void proxyIsSocks5WithUserPassOnLoopback() {
		String[] p = parse(XmrTorIsolation.syncProxy(9050, W1));
		assertEquals("socks5", p[0]);
		assertTrue(p[1].startsWith("zx-"));
		assertFalse(p[2].isEmpty());
		assertEquals("127.0.0.1:9050", p[3]);
	}

	@Test
	public void syncAndRelayOfOneWalletUseDifferentCredentials() {
		String[] sync = parse(XmrTorIsolation.syncProxy(9050, W1));
		String[] relay = parse(XmrTorIsolation.relayProxy(9050, W1));
		assertNotEquals("scan and broadcast must not share a circuit",
				sync[1], relay[1]);
	}

	@Test
	public void differentWalletsUseDifferentCredentials() {
		assertNotEquals(XmrTorIsolation.username(W1, XmrTorIsolation.PURPOSE_SYNC),
				XmrTorIsolation.username(W2, XmrTorIsolation.PURPOSE_SYNC));
	}

	@Test
	public void credentialsAreStableWithinTheProcess() {
		assertEquals(XmrTorIsolation.syncProxy(9050, W1),
				XmrTorIsolation.syncProxy(9050, W1));
	}

	@Test
	public void credentialsAreSafeForTheUriGrammarAndSocks5Limits() {
		for (String uri : new String[] {XmrTorIsolation.syncProxy(9050, W1),
				XmrTorIsolation.relayProxy(9050, W2)}) {
			String[] p = parse(uri);
			for (String field : new String[] {p[1], p[2]}) {
				assertTrue("only unreserved characters: " + field,
						field.matches("[a-z0-9-]+"));
				assertTrue("SOCKS5 fields are at most 255 bytes",
						field.length() <= 255);
			}
		}
	}

	@Test
	public void usernameDoesNotRevealTheWalletId() {
		String user = XmrTorIsolation.username(W1, XmrTorIsolation.PURPOSE_SYNC);
		assertFalse(user.contains(W1));
		assertFalse(user.contains("wallet"));
	}

	@Test
	public void secretChangesTheCredentialSoCircuitsDoNotSpanProcesses() {
		String a = XmrTorIsolation.proxyWithSecret(9050, W1,
				XmrTorIsolation.PURPOSE_SYNC, "aaaa");
		String b = XmrTorIsolation.proxyWithSecret(9050, W1,
				XmrTorIsolation.PURPOSE_SYNC, "bbbb");
		assertNotEquals(a, b);
	}
}
