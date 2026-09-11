package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.system.SystemClock;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies values produced by a second implementation of the Zerion wire
 * against this implementation: an ML-KEM-768 ciphertext it encapsulated to
 * one of our keys must decapsulate to the shared secret it reports, and its
 * ML-DSA-65 and hybrid signatures under our keys must verify. The file is
 * emitted by the other implementation's test suite from the vectors that
 * {@link CrossImplementationVectorsTest} produced; when it is absent this
 * test has nothing to check and passes.
 */
public class CrossImplementationVerifyTest {

	private static final String DEFAULT_IN = "build/cross-impl/ios-vectors.json";

	@Test
	public void verifyPeerVectors() throws Exception {
		String inPath = System.getenv("ZERION_VECTORS_IN");
		if (inPath == null || inPath.isEmpty()) inPath = DEFAULT_IN;
		File in = new File(inPath);
		if (!in.exists()) return;
		String json = new String(Files.readAllBytes(in.toPath()),
				StandardCharsets.UTF_8);

		SecureRandom random = new SecureRandom();
		CryptoComponentImpl crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));

		MlKem768 kem = new MlKem768(random);
		byte[] kemPriv = hex(section(json, "mlkem768", "privateKey"));
		byte[] kemCt = hex(section(json, "mlkem768", "ciphertext"));
		byte[] kemSs = hex(section(json, "mlkem768", "sharedSecret"));
		assertArrayEquals("ML-KEM-768 shared secret differs from the peer",
				kemSs, kem.decapsulate(kemPriv, kemCt));

		MlDsa65 dsa = new MlDsa65(random);
		byte[] dsaPub = hex(section(json, "mldsa65", "publicKey"));
		byte[] dsaMsg = hex(section(json, "mldsa65", "message"));
		byte[] dsaSig = hex(section(json, "mldsa65", "signature"));
		assertTrue("ML-DSA-65 signature from the peer does not verify",
				dsa.verify(dsaPub, dsaMsg, dsaSig));

		// The direction no shipping flow has ever exercised: encapsulating to a
		// key the peer generated. A steady-state connection needs this as soon
		// as it learns the key the peer advertises, so a format or validation
		// mismatch here would close every connection shortly after it opened.
		byte[] peerEk = hex(section(json, "locallyGeneratedKem", "publicKey"));
		byte[] peerDk = hex(section(json, "locallyGeneratedKem", "privateKey"));
		MlKem768.MlKemEncapsulation toPeer = kem.encapsulate(peerEk);
		assertArrayEquals("encapsulating to a peer-generated key must agree",
				toPeer.getSharedSecret(),
				kem.decapsulate(peerDk, toPeer.getCiphertext()));

		String label = section(json, "hybridSignature", "label");
		byte[] hybridPub = hex(section(json, "hybridSignature", "publicKey"));
		byte[] hybridMsg = hex(section(json, "hybridSignature", "message"));
		byte[] hybridSig = hex(section(json, "hybridSignature", "signature"));
		assertTrue("hybrid signature from the peer does not verify",
				crypto.verifyHybridSignature(hybridSig, label, hybridMsg,
						new HybridSignaturePublicKey(hybridPub)));
	}

	private static String section(String json, String section, String key) {
		int start = json.indexOf("\"" + section + "\"");
		assertTrue("missing section " + section, start >= 0);
		int end = json.indexOf("}", start);
		String body = json.substring(start, end);
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
				.matcher(body);
		assertTrue("missing key " + key + " in " + section, m.find());
		return m.group(1);
	}

	private static byte[] hex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}
}
