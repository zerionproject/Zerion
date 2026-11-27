package com.professor.zerion.android.util;

import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import static org.junit.Assert.assertTrue;

public class TlsProtocolsTest {

	@Test
	public void onlyTls12AndTls13StayEnabled() throws Exception {
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, null, null);
		try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
				.createSocket()) {
			ssl.setEnabledProtocols(ssl.getSupportedProtocols());
			TlsProtocols.restrict(ssl);
			assertTrue(LegacyTlsDefault.onlyModernProtocols(ssl));
		}
	}
}
