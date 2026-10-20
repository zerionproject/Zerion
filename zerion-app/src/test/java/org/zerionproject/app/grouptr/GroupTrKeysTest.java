package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.settings.SettingsManager;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupTrKeysTest {

	private final List<HybridSignaturePrivateKey> used = new ArrayList<>();
	private final List<byte[]> seen = new ArrayList<>();
	private KeyPair generated;

	private final GroupTrKeys keys = new GroupTrKeys(
			fake(SettingsManager.class), crypto(), fake(ClientHelper.class));

	@Test
	public void privateKeysAreClearedAfterUse() throws Exception {
		GroupTrKeys.Own own = keys.generate(1L);
		assertTrue("the generated private key outlived generation",
				zero(((HybridSignaturePrivateKey) generated.getPrivate())
						.getEncoded()));

		keys.sign(own, "label", new byte[] {1});
		assertEquals(1, used.size());
		assertFalse("the signature used the key", zero(seen.get(0)));
		assertTrue("the combined key outlived the signature",
				zero(used.get(0).getEncoded()));

		HybridSignaturePrivateKey identity = new HybridSignaturePrivateKey(
				filled(32), filled(PostQuantumConstants
				.ML_DSA_65_PRIVATE_KEY_BYTES));
		keys.bind(new byte[32], own, filled(32), identity, 1L);
		assertEquals(3, used.size());
		assertTrue(zero(used.get(2).getEncoded()));

		own.clear();
		keys.sign(own, "label", new byte[] {2});
		assertTrue("a cleared key still signed with its bytes",
				zero(seen.get(3)));
	}

	private static boolean zero(byte[] b) {
		for (byte x : b) if (x != 0) return false;
		return true;
	}

	private static byte[] filled(int n) {
		byte[] b = new byte[n];
		java.util.Arrays.fill(b, (byte) 3);
		return b;
	}

	private CryptoComponent crypto() {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class}, (p, m, a) -> {
					switch (m.getName()) {
						case "generateHybridSignatureKeyPair":
							generated = InviteTestNet.groupKeyPair();
							return generated;
						case "hybridSign": {
							HybridSignaturePrivateKey k =
									(HybridSignaturePrivateKey) a[2];
							used.add(k);
							seen.add(k.getEncoded().clone());
							return new byte[PostQuantumConstants
									.HYBRID_SIGNATURE_BYTES];
						}
						case "hash":
							return new byte[32];
						default:
							throw new UnsupportedOperationException(
									m.getName());
					}
				});
	}

	private static <T> T fake(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (p, m, a) -> {
					throw new UnsupportedOperationException(m.getName());
				}));
	}
}
