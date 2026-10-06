package com.professor.zerion.android.vault;

import android.app.Application;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;
import com.professor.zerion.android.vault.crypto.VaultKeystore;
import com.professor.zerion.android.vault.model.VaultHeader;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class VaultKeyFactorTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ALIAS = "zerion_vault_master_key_test";
	private static final char[] PASSWORD = "vault password 4&Rt".toCharArray();
	private static final char[] NEW_PASSWORD =
			"other vault password 6*Ux".toCharArray();

	private final Application app = RuntimeEnvironment.getApplication();
	private final List<String> derivations = new ArrayList<>();
	private File dir;
	private boolean strongFails = false;

	private final VaultManager.PasswordKdf kdf = (password, salt, params) -> {
		if (strongFails && params.memoryKb == 256 * 1024) {
			throw new OutOfMemoryError("injected");
		}
		derivations.add(params.memoryKb / 1024 + " MiB t=" + params.iterations);
		return fakeDerive(password, salt, params);
	};

	private static byte[] fakeDerive(char[] password, byte[] salt,
			Argon2.Argon2Params params) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(salt, "HmacSHA256"));
			mac.update(new String(password).getBytes(StandardCharsets.UTF_8));
			return mac.doFinal(params.toBytes());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Before
	public void setUp() throws Exception {
		dir = Files.createTempDirectory("vault-key-factor").toFile();
		clearKeyStore();
	}

	@After
	public void tearDown() throws Exception {
		clearKeyStore();
		deleteFileOrDir(dir);
	}

	private static void clearKeyStore() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : Collections.list(ks.aliases())) {
			ks.deleteEntry(alias);
		}
	}

	private static List<String> aliases() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		List<String> out = new ArrayList<>(Collections.list(ks.aliases()));
		Collections.sort(out);
		return out;
	}

	private VaultLocation location() {
		File vaultDir = new File(dir, "vault");
		return new VaultLocation() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public File directory() {
				return vaultDir;
			}

			@Override
			public String keyAlias() {
				return ALIAS;
			}

			@Override
			public List<File> allVaultDirectories() {
				return Collections.singletonList(vaultDir);
			}

			@Override
			public void onUnlocked() {
			}
		};
	}

	private VaultManager process() {
		return new VaultManager(app, location(), kdf);
	}

	private VaultHeader header() throws Exception {
		return VaultHeader.fromBytes(Files.readAllBytes(
				new File(location().directory(), "vault.header").toPath()));
	}

	private String unlock(char[] password) throws Exception {
		return process().unlockVault(password.clone()) ? "opens" : "refused";
	}

	@Test
	public void aNewVaultUsesTheDocumentedKeyDerivation() throws Exception {
		process().createVault(PASSWORD.clone());
		VaultHeader h = header();
		assertEquals("256 MiB t=3",
				h.kdfMemoryKb / 1024 + " MiB t=" + h.kdfIterations);
	}

	@Test
	public void onlyADeviceThatCannotAllocateGetsTheReducedDerivation()
			throws Exception {
		strongFails = true;
		process().createVault(PASSWORD.clone());
		VaultHeader h = header();
		assertEquals("128 MiB t=6, opens",
				h.kdfMemoryKb / 1024 + " MiB t=" + h.kdfIterations + ", "
						+ unlock(PASSWORD));
	}

	@Test
	public void aSecretUnwrappedOnceIsNotEnoughToTestPasswordsOffline()
			throws Exception {
		process().createVault(PASSWORD.clone());
		VaultHeader h = header();
		List<byte[]> secrets = new ArrayList<>();
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : Collections.list(ks.aliases())) {
			try {
				SecretKey k = (SecretKey) ks.getKey(alias, null);
				byte[] wrapped = h.wrappedKeystoreBlob;
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128,
						java.util.Arrays.copyOf(wrapped, 12)));
				secrets.add(c.doFinal(wrapped, 12, wrapped.length - 12));
			} catch (Exception notThisOne) {
			}
		}
		clearKeyStore();
		VaultCrypto crypto = new VaultCrypto();
		Argon2.Argon2Params params = new Argon2.Argon2Params(h.kdfMemoryKb,
				h.kdfIterations, h.kdfParallelism, 32);
		boolean confirmed = false;
		for (byte[] secret : secrets) {
			byte[] pk = fakeDerive(PASSWORD, h.salt, params);
			byte[] master = crypto.hkdfSha256(crypto.xor(pk, secret), h.salt,
					"vault master", 32);
			confirmed |= crypto.verifyPasswordMac(master,
					h.passwordVerificationMac);
		}
		assertEquals("secret unwrapped: yes, password confirmed offline: no",
				"secret unwrapped: " + (secrets.isEmpty() ? "no" : "yes")
						+ ", password confirmed offline: "
						+ (confirmed ? "yes" : "no"));
	}

	@Test
	public void everyUnlockNeedsTheKeyStore() throws Exception {
		process().createVault(PASSWORD.clone());
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : aliases()) {
			if (alias.endsWith("_ha") || alias.endsWith("_hb")) {
				ks.deleteEntry(alias);
			}
		}
		assertEquals("refused", unlock(PASSWORD));
	}

	@Test
	public void anEarlierCopyOfTheVaultStopsOpeningAfterAPasswordChange()
			throws Exception {
		VaultManager m = process();
		m.createVault(PASSWORD.clone());
		m.addItem(VaultItem.ItemType.NOTE, "note",
				"kept".getBytes(StandardCharsets.UTF_8));
		File vaultDir = location().directory();
		File copy = new File(dir, "copy");
		copyTree(vaultDir, copy);
		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());
		VaultManager after = process();
		boolean opens = after.unlockVault(NEW_PASSWORD.clone());
		String content = new String(after.getItemContent(
				after.listItems().get(0).id), StandardCharsets.UTF_8);

		deleteFileOrDir(vaultDir);
		copyTree(copy, vaultDir);
		assertEquals("new password opens, item kept, earlier copy with the"
						+ " old password refused",
				"new password " + (opens ? "opens" : "refused") + ", item "
						+ content + ", earlier copy with the old password "
						+ unlock(PASSWORD));
	}

	@Test
	public void aVaultOfAnEarlierVersionIsMovedOnItsNextUnlock()
			throws Exception {
		char[] typed = "vault​ password 4&Rt".toCharArray();
		VaultCrypto crypto = new VaultCrypto();
		VaultKeystore keystore = new VaultKeystore(app, location());
		SecretKey legacyKey = keystore.getOrCreateVaultKey();
		byte[] secret = crypto.generateKey();
		byte[] wrapped = keystore.wrapSecret(secret, legacyKey);
		byte[] salt = new Argon2().generateSalt();
		Argon2.Argon2Params low = Argon2.Argon2Params.getLowMemory();
		byte[] master = crypto.hkdfSha256(crypto.xor(
				fakeDerive(typed, salt, low), secret), salt, "vault master",
				32);
		VaultHeader v1 = VaultHeader.createNew(salt, low.memoryKb,
				low.iterations, wrapped, new byte[16],
				crypto.computePasswordVerificationMac(master));
		File vaultDir = location().directory();
		assertEquals(true, new File(vaultDir, "items").mkdirs());
		Files.write(new File(vaultDir, "vault.header").toPath(), v1.toBytes());
		writeItem(crypto, master, vaultDir, "note", "from 3.0.14");

		String first = unlock(typed);
		VaultHeader h = header();
		boolean legacyKeyLeft = aliases().contains(ALIAS);
		VaultManager m = process();
		boolean normalFormOpens = m.unlockVault(
				"vault password 4&Rt".toCharArray());
		String content = new String(m.getItemContent(
				m.listItems().get(0).id), StandardCharsets.UTF_8);
		assertEquals("first unlock opens, header version 2, 256 MiB t=3,"
						+ " legacy key deleted, normal form opens, item from"
						+ " 3.0.14",
				"first unlock " + first + ", header version " + h.version
						+ ", " + h.kdfMemoryKb / 1024 + " MiB t="
						+ h.kdfIterations + ", legacy key "
						+ (legacyKeyLeft ? "kept" : "deleted")
						+ ", normal form "
						+ (normalFormOpens ? "opens" : "refused")
						+ ", item " + content);
	}

	@Test
	public void theVaultPasswordIsUsedInItsNormalForm() throws Exception {
		process().createVault("vault​ password 4&Rt".toCharArray());
		assertEquals("opens", unlock("vault password 4&Rt".toCharArray()));
	}

	private static void writeItem(VaultCrypto crypto, byte[] master,
			File vaultDir, String name, String text) throws Exception {
		byte[] content = text.getBytes(StandardCharsets.UTF_8);
		byte[] itemKey = crypto.generateKey();
		byte[] encContent = crypto.encrypt(content, itemKey,
				name.getBytes(StandardCharsets.UTF_8)).toBytes();
		byte[] encKey = crypto.encrypt(itemKey, master, new byte[0]).toBytes();
		VaultItem item = VaultItem.createNew(VaultItem.ItemType.NOTE, name,
				content.length, encKey, crypto.generateNonce());
		File itemDir = new File(new File(vaultDir, "items"), item.id);
		assertEquals(true, itemDir.mkdirs());
		byte[] padded = new byte[4096];
		padded[0] = (byte) (encContent.length >> 24);
		padded[1] = (byte) (encContent.length >> 16);
		padded[2] = (byte) (encContent.length >> 8);
		padded[3] = (byte) encContent.length;
		System.arraycopy(encContent, 0, padded, 4, encContent.length);
		Files.write(new File(itemDir, "content.bin").toPath(), padded);
		Files.write(new File(itemDir, "header.bin").toPath(),
				crypto.encrypt(item.serializeMetadata(), master,
						item.id.getBytes()).toBytes());
	}

	private static void copyTree(File from, File to) throws Exception {
		if (from.isDirectory()) {
			to.mkdirs();
			File[] children = from.listFiles();
			if (children == null) return;
			for (File c : children) copyTree(c, new File(to, c.getName()));
		} else {
			Files.copy(from.toPath(), to.toPath());
		}
	}
}
