package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.system.SystemClock;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Emits primitive-level test vectors produced by this implementation so
 * that a second implementation of the Zerion wire can prove it agrees on
 * every cryptographic building block: ML-KEM-768 encapsulation, ML-DSA-65
 * signatures, the hybrid signature composition, the BLAKE2b key
 * derivation, MAC and hash framing, and the XSalsa20-Poly1305 cipher with
 * its MAC-first layout. Self-consistency is asserted here; the emitted file
 * is consumed by the other implementation's test suite.
 */
public class CrossImplementationVectorsTest {

	private static final String KDF_LABEL =
			"org.zerionproject/CROSS_IMPL_KDF";
	private static final String MAC_LABEL =
			"org.zerionproject/CROSS_IMPL_MAC";
	private static final String HASH_LABEL =
			"org.zerionproject/CROSS_IMPL_HASH";
	private static final String SIG_LABEL =
			"org.zerionproject/CROSS_IMPL_SIG";

	@Test
	public void emitVectors() throws Exception {
		SecureRandom random = new SecureRandom();
		CryptoComponentImpl crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));

		MlKem768 kem = new MlKem768(random);
		MlKem768.MlKemKeyPair kemKeys = kem.generateKeyPair();
		MlKem768.MlKemEncapsulation enc =
				kem.encapsulate(kemKeys.getPublicKey());
		byte[] decapsulated =
				kem.decapsulate(kemKeys.getPrivateKey(), enc.getCiphertext());
		assertArrayEquals(enc.getSharedSecret(), decapsulated);

		MlDsa65 dsa = new MlDsa65(random);
		MlDsa65.MlDsaKeyPair dsaKeys = dsa.generateKeyPair();
		byte[] dsaMessage = randomBytes(random, 97);
		byte[] dsaSignature = dsa.sign(dsaKeys.getPrivateKey(), dsaMessage);
		assertTrue(dsa.verify(dsaKeys.getPublicKey(), dsaMessage,
				dsaSignature));

		KeyPair hybridSig = crypto.generateHybridSignatureKeyPair();
		byte[] hybridMessage = randomBytes(random, 211);
		byte[] hybridSignature = crypto.hybridSign(SIG_LABEL, hybridMessage,
				hybridSig.getPrivate());
		assertTrue(crypto.verifyHybridSignature(hybridSignature, SIG_LABEL,
				hybridMessage, hybridSig.getPublic()));

		byte[] kdfKey = randomBytes(random, 32);
		byte[] kdfIn1 = randomBytes(random, 24);
		byte[] kdfIn2 = randomBytes(random, 8);
		byte[] kdfOut = crypto.deriveKey(KDF_LABEL, new SecretKey(kdfKey),
				kdfIn1, kdfIn2).getBytes();
		byte[] macKey = randomBytes(random, 32);
		byte[] macIn = randomBytes(random, 61);
		byte[] macOut = crypto.mac(MAC_LABEL, new SecretKey(macKey), macIn);
		byte[] hashIn1 = randomBytes(random, 33);
		byte[] hashIn2 = randomBytes(random, 0);
		byte[] hashIn3 = randomBytes(random, 130);
		byte[] hashOut = crypto.hash(HASH_LABEL, hashIn1, hashIn2, hashIn3);
		assertEquals(32, kdfOut.length);
		assertEquals(32, macOut.length);
		assertEquals(32, hashOut.length);

		byte[] aeadKey = randomBytes(random, 32);
		byte[] aeadNonce = randomBytes(random, 24);
		byte[] aeadPlaintext = randomBytes(random, 145);
		XSalsa20Poly1305AuthenticatedCipher cipher =
				new XSalsa20Poly1305AuthenticatedCipher();
		cipher.init(true, new SecretKey(aeadKey), aeadNonce);
		byte[] aeadCiphertext = new byte[aeadPlaintext.length
				+ cipher.getMacBytes()];
		int written = cipher.process(aeadPlaintext, 0, aeadPlaintext.length,
				aeadCiphertext, 0);
		assertEquals(aeadCiphertext.length, written);

		StringBuilder json = new StringBuilder();
		json.append("{\n");
		json.append("  \"mlkem768\": {\n");
		field(json, "publicKey", kemKeys.getPublicKey(), true);
		field(json, "privateKey", kemKeys.getPrivateKey(), true);
		field(json, "ciphertext", enc.getCiphertext(), true);
		field(json, "sharedSecret", enc.getSharedSecret(), false);
		json.append("  },\n");
		json.append("  \"mldsa65\": {\n");
		field(json, "publicKey", dsaKeys.getPublicKey(), true);
		field(json, "privateKey", dsaKeys.getPrivateKey(), true);
		field(json, "message", dsaMessage, true);
		field(json, "signature", dsaSignature, false);
		json.append("  },\n");
		json.append("  \"hybridSignature\": {\n");
		text(json, "label", SIG_LABEL, true);
		field(json, "publicKey", hybridSig.getPublic().getEncoded(), true);
		field(json, "privateKey", hybridSig.getPrivate().getEncoded(), true);
		field(json, "message", hybridMessage, true);
		field(json, "signature", hybridSignature, false);
		json.append("  },\n");
		json.append("  \"kdf\": {\n");
		text(json, "label", KDF_LABEL, true);
		field(json, "key", kdfKey, true);
		field(json, "input1", kdfIn1, true);
		field(json, "input2", kdfIn2, true);
		field(json, "output", kdfOut, false);
		json.append("  },\n");
		json.append("  \"mac\": {\n");
		text(json, "label", MAC_LABEL, true);
		field(json, "key", macKey, true);
		field(json, "input", macIn, true);
		field(json, "output", macOut, false);
		json.append("  },\n");
		json.append("  \"hash\": {\n");
		text(json, "label", HASH_LABEL, true);
		field(json, "input1", hashIn1, true);
		field(json, "input2", hashIn2, true);
		field(json, "input3", hashIn3, true);
		field(json, "output", hashOut, false);
		json.append("  },\n");
		json.append("  \"xsalsa20poly1305\": {\n");
		field(json, "key", aeadKey, true);
		field(json, "nonce", aeadNonce, true);
		field(json, "plaintext", aeadPlaintext, true);
		field(json, "ciphertext", aeadCiphertext, false);
		json.append("  }\n");
		json.append("}\n");

		String outPath = System.getenv("ZERION_VECTORS_OUT");
		if (outPath == null || outPath.isEmpty()) {
			outPath = "build/cross-impl/android-vectors.json";
		}
		File out = new File(outPath);
		File parent = out.getParentFile();
		if (parent != null && !parent.exists()) assertTrue(parent.mkdirs());
		try (Writer w = new OutputStreamWriter(new FileOutputStream(out),
				StandardCharsets.UTF_8)) {
			w.write(json.toString());
		}
	}

	private static byte[] randomBytes(SecureRandom random, int length) {
		byte[] b = new byte[length];
		random.nextBytes(b);
		return b;
	}

	private static void field(StringBuilder json, String name, byte[] value,
			boolean comma) {
		json.append("    \"").append(name).append("\": \"")
				.append(hex(value)).append('"');
		if (comma) json.append(',');
		json.append('\n');
	}

	private static void text(StringBuilder json, String name, String value,
			boolean comma) {
		json.append("    \"").append(name).append("\": \"").append(value)
				.append('"');
		if (comma) json.append(',');
		json.append('\n');
	}

	private static String hex(byte[] bytes) {
		char[] digits = "0123456789abcdef".toCharArray();
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int v = bytes[i] & 0xFF;
			out[i * 2] = digits[v >>> 4];
			out[i * 2 + 1] = digits[v & 0x0F];
		}
		return new String(out);
	}
}
