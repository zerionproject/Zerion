package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@NotNullByDefault
public final class TlsTrust {

	private TlsTrust() {
	}

	public static String sha256Hex(byte[] der) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(der);
			StringBuilder sb = new StringBuilder(h.length * 2);
			for (byte b : h) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	static boolean constEq(String a, String b) {
		byte[] x = a.getBytes();
		byte[] y = b.getBytes();
		if (x.length != y.length) {
			return false;
		}
		int r = 0;
		for (int i = 0; i < x.length; i++) {
			r |= x[i] ^ y[i];
		}
		return r == 0;
	}

	public static void verify(@Nullable String pinnedSha256, byte[] leafCertDer,
			boolean caTrusted, boolean hostnameOk) throws CertificateException {
		if (pinnedSha256 != null && !pinnedSha256.isEmpty()) {
			String actual = sha256Hex(leafCertDer);
			if (!constEq(actual, pinnedSha256.toLowerCase())) {
				throw new CertificateException("certificate pin mismatch");
			}
			return;
		}
		if (!caTrusted) {
			throw new CertificateException("certificate not trusted by any CA");
		}
		if (!hostnameOk) {
			throw new CertificateException("certificate hostname mismatch");
		}
	}

	public static SSLSocketFactory pinnedFactory(String pinSha256)
			throws GeneralSecurityException {
		final String pin = pinSha256.toLowerCase();
		X509TrustManager tm = new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain,
					String authType) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain,
					String authType) throws CertificateException {
				if (chain == null || chain.length == 0) {
					throw new CertificateException("no server certificate");
				}
				String actual = sha256Hex(chain[0].getEncoded());
				if (!constEq(actual, pin)) {
					throw new CertificateException("certificate pin mismatch");
				}
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(null, new TrustManager[]{tm}, null);
		return ctx.getSocketFactory();
	}
}
