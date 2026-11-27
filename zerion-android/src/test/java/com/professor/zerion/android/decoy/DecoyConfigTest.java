package com.professor.zerion.android.decoy;

import com.professor.zerion.android.security.MemorySharedPreferences;
import com.professor.zerion.android.vault.crypto.Argon2;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DecoyConfigTest {

	@Test
	public void aNewCodeUsesTheDatabaseKdfParametersAndVerifies() {
		MemorySharedPreferences p = new MemorySharedPreferences();
		DecoyConfig.setUnlockCode(p, "1234".toCharArray());
		assertEquals("2,131072,3,1", p.getString(DecoyConfig.PREF_PARAMS, null));
		assertTrue(DecoyConfig.verify(p, "1234".toCharArray()));
		assertFalse(DecoyConfig.verify(p, "1235".toCharArray()));
	}

	@Test
	public void aCodeStoredWithoutParametersVerifiesWithTheOldOnes() {
		MemorySharedPreferences p = new MemorySharedPreferences();
		byte[] salt = new byte[32];
		new SecureRandom().nextBytes(salt);
		byte[] hash = new Argon2().deriveKey("7777".toCharArray(), salt,
				DecoyConfig.legacyParams());
		p.values.put("pref_decoy_salt",
				Base64.getEncoder().withoutPadding().encodeToString(salt));
		p.values.put("pref_decoy_hash",
				Base64.getEncoder().withoutPadding().encodeToString(hash));
		assertTrue(DecoyConfig.verify(p, "7777".toCharArray()));
		assertFalse(DecoyConfig.verify(p, "7778".toCharArray()));
	}

	@Test
	public void parametersAreEncodedWithAVersionAndCheckedOnTheWayBack() {
		Argon2.Argon2Params current = DecoyConfig.currentParams();
		assertEquals(128 * 1024, current.memoryKb);
		assertEquals(3, current.iterations);
		assertEquals(1, current.parallelism);
		Argon2.Argon2Params decoded =
				DecoyConfig.decodeParams(DecoyConfig.encodeParams(current));
		assertNotNull(decoded);
		assertEquals(current.memoryKb, decoded.memoryKb);
		assertEquals(current.iterations, decoded.iterations);
		Argon2.Argon2Params legacy = DecoyConfig.decodeParams(null);
		assertNotNull(legacy);
		assertEquals(64 * 1024, legacy.memoryKb);
		assertNull(DecoyConfig.decodeParams("1,65536,3,1"));
		assertNull(DecoyConfig.decodeParams("2,1,3,1"));
		assertNull(DecoyConfig.decodeParams("2,131072,0,1"));
		assertNull(DecoyConfig.decodeParams("2,131072,2,1"));
		assertNotNull(DecoyConfig.decodeParams("2,131072,3,1"));
		assertNull(DecoyConfig.decodeParams("2,131072,11,1"));
		assertNull(DecoyConfig.decodeParams("2,131072,3,4"));
		assertNull(DecoyConfig.decodeParams("garbage"));
	}

	@Test
	public void unreadableParametersRefuseTheCode() {
		MemorySharedPreferences p = new MemorySharedPreferences();
		DecoyConfig.setUnlockCode(p, "2468".toCharArray());
		p.values.put(DecoyConfig.PREF_PARAMS, "9,131072,3,1");
		assertFalse(DecoyConfig.verify(p, "2468".toCharArray()));
	}
}
