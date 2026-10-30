package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.net.TorSockets;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ElectrumClientTofuTest {

	private static final String HOST = "node.example.org";
	private static final char[] PASSWORD = "test".toCharArray();
	private static final AtomicLong SERIAL = new AtomicLong();

	private static final class Server {
		final X509Certificate leaf;
		final SSLContext context;

		Server(X509Certificate leaf, SSLContext context) {
			this.leaf = leaf;
			this.context = context;
		}
	}

	private ServerSocket proxy;
	private SSLContext previousDefault;
	private HostnameVerifier previousVerifier;
	private KeyPair caKeys;
	private X509Certificate ca;
	private volatile List<Server> circuits = new ArrayList<>();
	private final List<String> users = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() throws Exception {
		caKeys = keys();
		ca = certificate("Test CA", caKeys.getPublic(), "Test CA",
				caKeys.getPrivate(), true);
		KeyStore trust = KeyStore.getInstance("PKCS12");
		trust.load(null, null);
		trust.setCertificateEntry("ca", ca);
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(
				TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(trust);
		SSLContext client = SSLContext.getInstance("TLS");
		client.init(null, tmf.getTrustManagers(), null);
		previousDefault = SSLContext.getDefault();
		previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
		SSLContext.setDefault(client);
		HttpsURLConnection.setDefaultHostnameVerifier(
				(host, session) -> HOST.equals(host));
		proxy = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		InetSocketAddress address = new InetSocketAddress(
				InetAddress.getLoopbackAddress(), proxy.getLocalPort());
		TorSockets.install(timeoutMs -> {
			Socket s = new Socket();
			s.connect(address, timeoutMs);
			return s;
		});
		Thread t = new Thread(this::serve, "tofu-proxy");
		t.setDaemon(true);
		t.start();
	}

	@After
	public void tearDown() throws IOException {
		TorSockets.install(timeoutMs -> {
			throw new IOException("Tor proxy not available");
		});
		SSLContext.setDefault(previousDefault);
		HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier);
		proxy.close();
	}

	private void serve() {
		while (!proxy.isClosed()) {
			Socket c;
			try {
				c = proxy.accept();
			} catch (IOException closed) {
				return;
			}
			Thread h = new Thread(() -> {
				try (Socket s = c) {
					handle(s);
				} catch (Exception ignored) {
				}
			}, "tofu-circuit");
			h.setDaemon(true);
			h.start();
		}
	}

	private void handle(Socket s) throws Exception {
		s.setSoTimeout(10_000);
		DataInputStream in = new DataInputStream(s.getInputStream());
		OutputStream out = s.getOutputStream();
		in.readFully(new byte[3]);
		out.write(new byte[] {5, 2});
		in.readUnsignedByte();
		String user = new String(read(in, in.readUnsignedByte()),
				StandardCharsets.US_ASCII);
		read(in, in.readUnsignedByte());
		out.write(new byte[] {1, 0});
		in.readFully(new byte[4]);
		read(in, in.readUnsignedByte());
		in.readUnsignedShort();
		out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
		out.flush();
		Server server = circuitFor(user);
		SSLSocket tls = (SSLSocket) server.context.getSocketFactory()
				.createSocket(s, null, s.getPort(), false);
		tls.setUseClientMode(false);
		tls.startHandshake();
		tls.getInputStream().read();
	}

	private synchronized Server circuitFor(String user) {
		if (!users.contains(user)) users.add(user);
		List<Server> c = circuits;
		return c.get(Math.min(users.indexOf(user), c.size() - 1));
	}

	private static byte[] read(DataInputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		in.readFully(b);
		return b;
	}

	private static KeyPair keys() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	private static X509Certificate certificate(String subject, PublicKey key,
			String issuer, PrivateKey signer, boolean ca) throws Exception {
		V3TBSCertificateGenerator g = new V3TBSCertificateGenerator();
		g.setSerialNumber(new ASN1Integer(BigInteger.valueOf(
				SERIAL.incrementAndGet())));
		g.setIssuer(new X500Name("CN=" + issuer));
		g.setSubject(new X500Name("CN=" + subject));
		long now = System.currentTimeMillis();
		g.setStartDate(new Time(new Date(now - 3_600_000L)));
		g.setEndDate(new Time(new Date(now + 86_400_000L)));
		g.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(
				key.getEncoded()));
		AlgorithmIdentifier alg =
				new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
		g.setSignature(alg);
		ExtensionsGenerator ext = new ExtensionsGenerator();
		if (ca) {
			ext.addExtension(Extension.basicConstraints, true,
					new BasicConstraints(true));
		} else {
			ext.addExtension(Extension.subjectAlternativeName, false,
					new GeneralNames(new GeneralName(GeneralName.dNSName,
							HOST)));
		}
		g.setExtensions(ext.generate());
		TBSCertificate tbs = g.generateTBSCertificate();
		Signature sig = Signature.getInstance("SHA256withECDSA");
		sig.initSign(signer);
		sig.update(tbs.getEncoded(ASN1Encoding.DER));
		ASN1EncodableVector v = new ASN1EncodableVector();
		v.add(tbs);
		v.add(alg);
		v.add(new DERBitString(sig.sign()));
		byte[] der = new DERSequence(v).getEncoded(ASN1Encoding.DER);
		return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(der));
	}

	private static Server server(KeyPair keys, X509Certificate... chain)
			throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(null, null);
		ks.setKeyEntry("k", keys.getPrivate(), PASSWORD, chain);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(
				KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, PASSWORD);
		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), null, null);
		return new Server(chain[0], ctx);
	}

	private static Server selfSigned() throws Exception {
		KeyPair k = keys();
		return server(k, certificate(HOST, k.getPublic(), HOST,
				k.getPrivate(), false));
	}

	private Server publiclyTrusted() throws Exception {
		KeyPair k = keys();
		return server(k, certificate(HOST, k.getPublic(), "Test CA",
				caKeys.getPrivate(), false), ca);
	}

	private static String fingerprint(Server s) throws Exception {
		return TlsTrust.sha256Hex(s.leaf.getEncoded());
	}

	private static ElectrumEndpoint endpoint() {
		return new ElectrumEndpoint(HOST, 50002, ElectrumEndpoint.Mode.TLS,
				false, null);
	}

	private ElectrumClient.CapturedCert capture(Server... perCircuit)
			throws IOException {
		circuits = Arrays.asList(perCircuit);
		return ElectrumClient.captureCert(endpoint(), 9050, 20_000);
	}

	private void assertTwoCircuits() {
		assertEquals(users.toString(), 2, users.size());
		assertNotEquals(users.get(0), users.get(1));
		for (String u : users) assertTrue(u, u.startsWith("zw-tofu-"));
	}

	@Test(timeout = 30_000)
	public void aSelfSignedCertificateSeenOnTwoCircuitsIsOffered()
			throws Exception {
		Server node = selfSigned();
		ElectrumClient.CapturedCert c = capture(node, node);
		assertFalse(c.caValid);
		assertEquals(fingerprint(node), c.sha256);
		assertTwoCircuits();
	}

	@Test(timeout = 30_000)
	public void aTrustedCertificateSeenOnTwoCircuitsIsOffered()
			throws Exception {
		Server node = publiclyTrusted();
		ElectrumClient.CapturedCert c = capture(node, node);
		assertTrue(c.caValid);
		assertEquals(fingerprint(node), c.sha256);
		assertTwoCircuits();
	}

	@Test(timeout = 30_000)
	public void differentCertificatesOnTwoCircuitsAreRefused()
			throws Exception {
		try {
			capture(selfSigned(), selfSigned());
			fail("a certificate one exit could have swapped was offered");
		} catch (ElectrumClient.CertificatesDiffer expected) {
		}
		assertTwoCircuits();
	}

	@Test(timeout = 30_000)
	public void aTrustedCertificateHiddenOnTheFirstCircuitIsInterception()
			throws Exception {
		try {
			capture(selfSigned(), publiclyTrusted());
			fail("the prompt for a certificate without an authority was offered");
		} catch (ElectrumClient.PossibleInterception expected) {
		}
		assertTwoCircuits();
	}

	@Test(timeout = 30_000)
	public void aTrustedCertificateHiddenOnTheSecondCircuitIsInterception()
			throws Exception {
		try {
			capture(publiclyTrusted(), selfSigned());
			fail("a certificate one exit could have swapped was offered");
		} catch (ElectrumClient.PossibleInterception expected) {
		}
		assertTwoCircuits();
	}
}
