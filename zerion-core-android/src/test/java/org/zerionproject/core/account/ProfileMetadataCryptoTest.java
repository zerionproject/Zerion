package org.zerionproject.core.account;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileMetadataCryptoTest {

	private final File testDir = getTestDirectory();

	static final class FakeKeys implements ProfileMetadataCrypto.KeyProvider {

		final Map<String, SecretKey> keys = new HashMap<>();
		boolean deviceLocked = false;
		boolean refuseDeviceBound = false;
		int lookups = 0;

		@Override
		@Nullable
		public SecretKey get(String alias, boolean create)
				throws GeneralSecurityException {
			lookups++;
			boolean deviceBound =
					alias.equals(ProfileMetadataCrypto.KEY_ALIAS);
			if (deviceBound && deviceLocked) {
				throw new InvalidKeyException("Key user not authenticated");
			}
			SecretKey k = keys.get(alias);
			if (k != null || !create) return k;
			if (deviceBound && refuseDeviceBound) {
				throw new GeneralSecurityException("refused");
			}
			KeyGenerator gen = KeyGenerator.getInstance("AES");
			gen.init(256);
			k = gen.generateKey();
			keys.put(alias, k);
			return k;
		}
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private File file(String name) {
		testDir.mkdirs();
		return new File(testDir, name);
	}

	@Test
	public void aFreshWriteUsesTheDeviceBoundKeyOnly() throws Exception {
		FakeKeys keys = new FakeKeys();
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("display_name");
		crypto.writeEncrypted(f, "Alice");
		assertTrue(keys.keys.containsKey(ProfileMetadataCrypto.KEY_ALIAS));
		assertFalse(keys.keys.containsKey(
				ProfileMetadataCrypto.FALLBACK_KEY_ALIAS));
		assertEquals("Alice", crypto.readEncrypted(f));
	}

	@Test
	public void aFileUnderTheOldKeyIsReadAndMovedToTheDeviceBoundKey()
			throws Exception {
		FakeKeys old = new FakeKeys();
		SecretKey fallback = old.get(ProfileMetadataCrypto.FALLBACK_KEY_ALIAS,
				true);
		ProfileMetadataCrypto writer = new ProfileMetadataCrypto(
				(alias, create) -> alias.equals(
						ProfileMetadataCrypto.FALLBACK_KEY_ALIAS)
						? fallback : null);
		File f = file("display_name");
		writer.writeEncrypted(f, "Bob");
		byte[] before = Files.readAllBytes(f.toPath());

		FakeKeys keys = new FakeKeys();
		keys.keys.put(ProfileMetadataCrypto.FALLBACK_KEY_ALIAS, fallback);
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		assertEquals("Bob", crypto.readEncrypted(f));
		assertTrue(keys.keys.containsKey(ProfileMetadataCrypto.KEY_ALIAS));
		byte[] after = Files.readAllBytes(f.toPath());
		assertFalse(java.util.Arrays.equals(before, after));

		keys.keys.remove(ProfileMetadataCrypto.FALLBACK_KEY_ALIAS);
		assertEquals("Bob", crypto.readEncrypted(f));
	}

	@Test
	public void aLockedDeviceReportsTheKeyAsUnavailable() throws Exception {
		FakeKeys keys = new FakeKeys();
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("display_name");
		crypto.writeEncrypted(f, "Carol");
		byte[] sealed = Files.readAllBytes(f.toPath());
		keys.deviceLocked = true;
		try {
			crypto.readEncrypted(f);
			throw new AssertionError("expected the key to be unavailable");
		} catch (ProfileMetadataCrypto.KeyUnavailableException expected) {
		}
		assertArrayEquals(sealed, Files.readAllBytes(f.toPath()));
		keys.deviceLocked = false;
		assertEquals("Carol", crypto.readEncrypted(f));
	}

	@Test
	public void aFileNoKeyCanOpenIsNotDecryptableNotUnavailable()
			throws Exception {
		FakeKeys keys = new FakeKeys();
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("display_name");
		crypto.writeEncrypted(f, "Carol");
		keys.keys.clear();
		assertNull("no key exists, so the file is not sealed for us",
				crypto.readEncrypted(f));
		keys.get(ProfileMetadataCrypto.KEY_ALIAS, true);
		assertNull("a different key means a bad tag, not a locked key",
				crypto.readEncrypted(f));
	}

	@Test
	public void aResealHappensOnceAndLeavesNoTemporaryFile()
			throws Exception {
		FakeKeys old = new FakeKeys();
		SecretKey fallback = old.get(ProfileMetadataCrypto.FALLBACK_KEY_ALIAS,
				true);
		ProfileMetadataCrypto writer = new ProfileMetadataCrypto(
				(alias, create) -> alias.equals(
						ProfileMetadataCrypto.FALLBACK_KEY_ALIAS)
						? fallback : null);
		File f = file("display_name");
		writer.writeEncrypted(f, "Heidi");
		assertFalse(new File(testDir, "display_name.tmp").exists());
		byte[] underOldKey = Files.readAllBytes(f.toPath());

		FakeKeys keys = new FakeKeys();
		keys.keys.put(ProfileMetadataCrypto.FALLBACK_KEY_ALIAS, fallback);
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		assertEquals("Heidi", crypto.readEncrypted(f));
		byte[] resealed = Files.readAllBytes(f.toPath());
		assertFalse(java.util.Arrays.equals(underOldKey, resealed));
		assertFalse(new File(testDir, "display_name.tmp").exists());

		Files.write(f.toPath(), underOldKey);
		assertEquals("Heidi", crypto.readEncrypted(f));
		assertArrayEquals("the same file is not resealed twice",
				underOldKey, Files.readAllBytes(f.toPath()));
		assertEquals("Heidi", new ProfileMetadataCrypto(keys).readEncrypted(f));
		assertFalse(java.util.Arrays.equals(underOldKey,
				Files.readAllBytes(f.toPath())));
	}

	@Test
	public void aLockedDeviceWritesUnderTheFallbackKey() throws Exception {
		FakeKeys keys = new FakeKeys();
		keys.deviceLocked = true;
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("pending_identity_name");
		crypto.writeEncrypted(f, "Dave");
		assertTrue(keys.keys.containsKey(
				ProfileMetadataCrypto.FALLBACK_KEY_ALIAS));
		assertFalse(keys.keys.containsKey(ProfileMetadataCrypto.KEY_ALIAS));
		keys.deviceLocked = false;
		assertEquals("Dave", crypto.readEncrypted(f));
		assertTrue("moved to the device bound key once it is usable",
				keys.keys.containsKey(ProfileMetadataCrypto.KEY_ALIAS));
	}

	@Test
	public void aDeviceThatRefusesTheBoundKeyFallsBack() throws Exception {
		FakeKeys keys = new FakeKeys();
		keys.refuseDeviceBound = true;
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("display_name");
		crypto.writeEncrypted(f, "Erin");
		assertEquals("Erin", crypto.readEncrypted(f));
		assertFalse(keys.keys.containsKey(ProfileMetadataCrypto.KEY_ALIAS));
	}

	@Test
	public void noUsableKeyIsAnIoExceptionNotACrash() throws Exception {
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(
				(alias, create) -> {
					throw new IllegalStateException("keystore down");
				});
		File f = file("display_name");
		try {
			crypto.writeEncrypted(f, "Frank");
			throw new AssertionError("expected an IOException");
		} catch (IOException expected) {
		}
		assertFalse(f.exists());
		assertNull(crypto.readEncrypted(f));
		new ProfileMetadataCrypto(new FakeKeys()).writeEncrypted(f, "Frank");
		try {
			crypto.readEncrypted(f);
			throw new AssertionError("expected the key to be unavailable");
		} catch (ProfileMetadataCrypto.KeyUnavailableException expected) {
		}
	}

	@Test
	public void aDamagedFileReadsAsNull() throws Exception {
		FakeKeys keys = new FakeKeys();
		ProfileMetadataCrypto crypto = new ProfileMetadataCrypto(keys);
		File f = file("display_name");
		Files.write(f.toPath(), new byte[] {1, 2, 3});
		assertNull(crypto.readEncrypted(f));
		crypto.writeEncrypted(f, "Grace");
		byte[] sealed = Files.readAllBytes(f.toPath());
		sealed[sealed.length - 1] ^= 1;
		Files.write(f.toPath(), sealed);
		assertNull(crypto.readEncrypted(f));
		assertNotNull(keys.keys.get(ProfileMetadataCrypto.KEY_ALIAS));
	}
}
