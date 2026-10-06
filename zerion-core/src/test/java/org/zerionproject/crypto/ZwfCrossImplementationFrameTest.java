package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZwfCrossImplementationFrameTest {

	private static final String A_ROOT =
			"org.zerionproject.transport/DIR_A_ROOT";
	private static final String A_HDR =
			"org.zerionproject.transport/DIR_A_HEADER";
	private static final int EK_SEED_SIZE = 32;

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				Thread.sleep(ms);
			}
		};
		ratchet = new PcsRatchetImpl(crypto);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKemProvider = (MlKemProvider) providerCtor
				.newInstance(crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(
				crypto, mlKemProvider);
	}

	@Test
	public void opensFrameSealedByPeerImplementation() throws Exception {
		String inPath = System.getenv("ZERION_VECTORS_IN");
		if (inPath == null || inPath.isEmpty()) {
			inPath = "build/cross-impl/ios-vectors.json";
		}
		File in = new File(inPath);
		if (!in.exists()) return;
		String json = new String(Files.readAllBytes(in.toPath()),
				StandardCharsets.UTF_8);

		byte[] wire = hex(section(json, "zwfFrame", "wire"));
		byte[] expectedPayload = hex(section(json, "zwfFrame", "payload"));
		SecretKey rootKey =
				new SecretKey(hex(section(json, "zwfFrame", "rootKey")));

		SecretKey recvRootKey = crypto.deriveKey(A_ROOT, rootKey);
		SecretKey recvHeaderKey = crypto.deriveKey(A_HDR, rootKey);

		byte[] ek = hex(section(json, "mlkem768", "publicKey"));
		byte[] dk = hex(section(json, "mlkem768", "privateKey"));
		MlKemKeyPair ourKeyPair = new MlKemKeyPair(ek, dk,
				Arrays.copyOf(ek, EK_SEED_SIZE),
				Arrays.copyOfRange(ek, EK_SEED_SIZE, ek.length));
		Mode3FullState m3f = new Mode3FullState(null, ourKeyPair,
				new LinkedHashMap<>(), 0);

		KeyPair dhKp = crypto.generateAgreementKeyPair();
		PcsSessionState recvState = PcsSessionState.createInitialMode3Full(
				recvRootKey, recvRootKey, new DhRatchetState(dhKp, null), m3f);

		byte[] tag = Arrays.copyOf(wire, 16);
		AuthenticatedCipher cipher = new XSalsa20Poly1305AuthenticatedCipher();
		ZwfMode3FullStreamDecrypter dec = new ZwfMode3FullStreamDecrypter(
				new ByteArrayInputStream(wire), cipher, ratchet,
				mode3FullRatchet, tag, 0L, recvHeaderKey, recvState, null);

		byte[] buf = new byte[FRAME_LENGTH];
		int n = dec.readFrame(buf);
		assertTrue("expected a payload, got " + n, n > 0);
		assertArrayEquals("the peer's frame must open to the payload it sealed",
				expectedPayload, Arrays.copyOf(buf, n));
		assertEquals(7L, dec.getStreamId());

		byte[] expectedPayload2 = hex(section(json, "zwfFrame", "payload2"));
		int n2 = dec.readFrame(buf);
		assertTrue("expected a second payload, got " + n2, n2 > 0);
		assertArrayEquals("the chain advance must agree between frames",
				expectedPayload2, Arrays.copyOf(buf, n2));
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
