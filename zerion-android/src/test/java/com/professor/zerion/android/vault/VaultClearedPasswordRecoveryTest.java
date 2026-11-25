package com.professor.zerion.android.vault;

import android.app.Application;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.crypto.VaultCrypto;
import com.professor.zerion.android.vault.crypto.VaultKdfTier;
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
import org.robolectric.shadows.ShadowSystemClock;
import org.zerionproject.core.account.PasswordNormalizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		FailingDirectorySyncShadow.class})
public class VaultClearedPasswordRecoveryTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ALIAS = "zerion_vault_master_key_test";
	private static final char[] PASSWORD = "vault password 4&Rt".toCharArray();
	private static final char[] CHOSEN = "Vaultnew67890".toCharArray();
	private static final char[] SAME_LENGTH = "Wrongpass1234".toCharArray();
	private static final char[] LONGER =
			"another password 7#Kp".toCharArray();
	private static final char[] MINIMUM = "Eight8&x".toCharArray();
	private static final char[] SHORT = "Seven7&".toCharArray();
	private static final char[] NEW_PASSWORD =
			"other vault password 6*Ux".toCharArray();
	private static final byte[] BIOMETRIC_SALT = {1, 2, 3, 4, 5, 6, 7, 8, 9,
			10, 11, 12, 13, 14, 15, 16};
	private static final long CREATED = 1_700_000_000_000L;
	private static final Argon2.Argon2Params A = VaultKdfTier.TIER_A;
	private static final Argon2.Argon2Params C = VaultKdfTier.TIER_C;

	private final Application app = RuntimeEnvironment.getApplication();
	private final List<String> derivations = new ArrayList<>();
	private boolean sealFails = false;
	private boolean syncFailsAtSeal = false;
	private boolean clearedFormTried = false;
	private File dir;

	private final VaultManager.PasswordKdf kdf = (password, salt, params) -> {
		derivations.add(form(password));
		if (cleared(password)) {
			clearedFormTried = true;
		} else if (clearedFormTried) {
			if (sealFails) throw new OutOfMemoryError("injected");
			if (syncFailsAtSeal) FailingDirectorySyncShadow.fail = true;
		}
		return fakeDerive(password, salt, params);
	};

	private static boolean cleared(char[] password) {
		for (char c : password) {
			if (c != 0) return false;
		}
		return true;
	}

	private static String form(char[] password) {
		if (password.length == 0) return "empty";
		return cleared(password) ? "zeros " + password.length : "typed";
	}

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
		dir = Files.createTempDirectory("vault-cleared-password").toFile();
		FailingDirectorySyncShadow.fail = false;
		clearKeyStore();
	}

	@After
	public void tearDown() throws Exception {
		FailingDirectorySyncShadow.fail = false;
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

	private File vaultDir() {
		return new File(dir, "vault");
	}

	private VaultLocation location() {
		File vaultDir = vaultDir();
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

	private VaultManager phone(Argon2.Argon2Params tier) {
		return new VaultManager(app, location(), kdf, () -> tier);
	}

	private VaultManager process() {
		return phone(C);
	}

	private void startOver() throws Exception {
		deleteFileOrDir(vaultDir());
		clearKeyStore();
	}

	private void sealUnderTheEmptyPassword() throws Exception {
		sealUnderTheEmptyPassword(C);
	}

	private void sealUnderTheEmptyPassword(Argon2.Argon2Params params)
			throws Exception {
		sealVersionTwo(new char[0], params);
	}

	private void sealVersionTwo(char[] sealedUnder,
			Argon2.Argon2Params params) throws Exception {
		VaultCrypto crypto = new VaultCrypto();
		VaultKeystore keystore = new VaultKeystore(app, location());
		int slot = VaultKeystore.SLOT_A;
		keystore.createSlot(slot);
		byte[] secret = crypto.generateKey();
		byte[] wrapped = keystore.wrapSecret(secret, keystore.wrapKey(slot));
		byte[] salt = new Argon2().generateSalt();
		byte[] hashed = keystore.hmac(slot,
				fakeDerive(sealedUnder, salt, params));
		byte[] kek = crypto.hkdfSha256(crypto.xor(hashed, secret), salt,
				"vault kek v2", 32);
		byte[] master = crypto.generateKey();
		byte[] label = "zerion-vault-master-v2".getBytes(
				StandardCharsets.UTF_8);
		byte[] aad = Arrays.copyOf(label, label.length + salt.length);
		System.arraycopy(salt, 0, aad, label.length, salt.length);
		VaultHeader header = VaultHeader.createKeystoreFactor(salt,
				params.memoryKb, params.iterations, wrapped, BIOMETRIC_SALT,
				crypto.computePasswordVerificationMac(master), CREATED, slot,
				crypto.encrypt(master, kek, aad).toBytes());
		store(header, crypto, master);
	}

	private void sealUnderZeros(int typedLength) throws Exception {
		sealVersionOne(new char[typedLength]);
	}

	private void sealVersionOne(char[] sealedUnder) throws Exception {
		VaultCrypto crypto = new VaultCrypto();
		VaultKeystore keystore = new VaultKeystore(app, location());
		SecretKey legacyKey = keystore.getOrCreateVaultKey();
		byte[] secret = crypto.generateKey();
		byte[] wrapped = keystore.wrapSecret(secret, legacyKey);
		byte[] salt = new Argon2().generateSalt();
		byte[] master = crypto.hkdfSha256(crypto.xor(
				fakeDerive(sealedUnder, salt, C), secret), salt,
				"vault master", 32);
		VaultHeader header = VaultHeader.createNew(salt, C.memoryKb,
				C.iterations, wrapped, BIOMETRIC_SALT,
				crypto.computePasswordVerificationMac(master));
		store(header, crypto, master);
	}

	private void store(VaultHeader header, VaultCrypto crypto, byte[] master)
			throws Exception {
		File vaultDir = vaultDir();
		assertTrue(new File(vaultDir, "items").mkdirs());
		Files.write(headerFile().toPath(), header.toBytes());
		byte[] content = "kept".getBytes(StandardCharsets.UTF_8);
		byte[] itemKey = crypto.generateKey();
		byte[] encContent = crypto.encrypt(content, itemKey,
				"Testnote".getBytes(StandardCharsets.UTF_8)).toBytes();
		byte[] encKey = crypto.encrypt(itemKey, master, new byte[0]).toBytes();
		VaultItem item = VaultItem.createNew(VaultItem.ItemType.NOTE,
				"Testnote", content.length, encKey, crypto.generateNonce());
		File itemDir = new File(new File(vaultDir, "items"), item.id);
		assertTrue(itemDir.mkdirs());
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

	private File headerFile() {
		return new File(vaultDir(), "vault.header");
	}

	private byte[] headerBytes() throws Exception {
		return Files.readAllBytes(headerFile().toPath());
	}

	private VaultHeader header() throws Exception {
		return VaultHeader.fromBytes(headerBytes());
	}

	private static String label(VaultHeader h) {
		return "version " + h.version + ", " + h.kdfMemoryKb / 1024
				+ " MiB t=" + h.kdfIterations;
	}

	private int failures() throws Exception {
		File f = new File(vaultDir(), "unlock.throttle");
		if (!f.exists() || f.length() == 0) return 0;
		String state = new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
		return Integer.parseInt(state.split(",", -1)[1]);
	}

	private void nextAttempt() {
		ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
		FailingDirectorySyncShadow.fail = false;
		clearedFormTried = false;
		derivations.clear();
	}

	private static String word(VaultManager.UnlockResult result) {
		switch (result) {
			case OPENED:
				return "opens";
			case REFUSED:
				return "refused";
			case CONFIRM_NEEDED:
				return "asks again";
			case ENTRIES_DIFFER:
				return "entries differ";
			default:
				return "not confirmed on storage";
		}
	}

	private String attempt(VaultManager m, char[] typed) throws Exception {
		nextAttempt();
		return word(m.unlock(typed.clone()));
	}

	private String confirm(VaultManager m, char[] first, char[] typed)
			throws Exception {
		nextAttempt();
		return word(m.confirmAndUnlock(first.clone(), typed.clone()));
	}

	private String twice(VaultManager m, char[] typed) throws Exception {
		assertEquals("asks again", attempt(m, typed));
		return confirm(m, typed, typed);
	}

	private String twice(char[] typed) throws Exception {
		return twice(process(), typed);
	}

	private String attempt(char[] typed) throws Exception {
		return attempt(process(), typed);
	}

	private boolean check(char[] typed) {
		nextAttempt();
		return process().verifyMasterPassword(typed.clone());
	}

	private static String note(VaultManager m) throws Exception {
		List<VaultItem> items = m.listItems();
		assertEquals(1, items.size());
		return items.get(0).name + " " + new String(
				m.getItemContent(items.get(0).id), StandardCharsets.UTF_8);
	}

	@Test
	public void theClearedFormsAreWhatTheOldReleasesFedTheKeyDerivation() {
		assertEquals(0, PasswordNormalizer.normalize(
				new char[CHOSEN.length]).length);
		Argon2.Argon2Params small = new Argon2.Argon2Params(
				Argon2.MIN_MEMORY_KB, 1, 1, 32);
		byte[] salt = new byte[16];
		Arrays.fill(salt, (byte) 7);
		Argon2 argon2 = new Argon2();
		assertArrayEquals(
				Argon2.deriveKeyBouncyCastle(new byte[0], salt, small),
				argon2.deriveKey(new char[0], salt, small));
		assertArrayEquals(
				Argon2.deriveKeyBouncyCastle(new byte[CHOSEN.length], salt,
						small),
				argon2.deriveKey(new char[CHOSEN.length], salt, small));
	}

	@Test
	public void aVaultSealedUnderTheEmptyPasswordOpensAfterTwoEqualEntries()
			throws Exception {
		sealUnderTheEmptyPassword();
		byte[] before = headerBytes();
		List<String> keys = aliases();
		VaultManager m = process();

		assertEquals("asks again", attempt(m, CHOSEN));

		assertEquals(Arrays.asList("typed", "empty"), derivations);
		assertFalse(m.isUnlocked());
		assertThrows(SecurityException.class, m::listItems);
		assertEquals(0, failures());
		assertArrayEquals(before, headerBytes());
		assertEquals(keys, aliases());

		assertEquals("opens", confirm(m, CHOSEN, CHOSEN));

		assertEquals(Arrays.asList("typed", "empty", "typed"), derivations);
		assertTrue(m.isUnlocked());
		assertEquals("Testnote kept", note(m));
		assertEquals(0, failures());
		assertFalse(Arrays.equals(before, headerBytes()));
		VaultHeader after = header();
		assertEquals("version 2, 256 MiB t=3", label(after));
		assertEquals(VaultKeystore.SLOT_B, after.keySlot);
		assertArrayEquals(BIOMETRIC_SALT, after.biometricTokenSalt);
		assertEquals(CREATED, after.createdTimestamp);
		assertEquals(2, aliases().size());
		m.lockVault();

		VaultManager later = process();
		assertEquals("opens", attempt(later, CHOSEN));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals("Testnote kept", note(later));
		assertTrue(check(CHOSEN));
		assertEquals("refused", attempt(new char[0]));
		assertEquals("refused", attempt(new char[CHOSEN.length]));
		assertEquals("refused", attempt(SAME_LENGTH));
		assertEquals("refused", attempt(LONGER));
		assertEquals("opens", attempt(CHOSEN));
	}

	@Test
	public void aVaultSealedUnderZerosOpensAfterTwoEqualEntries()
			throws Exception {
		sealUnderZeros(CHOSEN.length);
		byte[] before = headerBytes();
		long created = header().createdTimestamp;
		assertEquals(Collections.singletonList(ALIAS), aliases());
		VaultManager m = process();

		assertEquals("asks again", attempt(m, CHOSEN));

		assertEquals(Arrays.asList("typed", "zeros " + CHOSEN.length),
				derivations);
		assertFalse(m.isUnlocked());
		assertThrows(SecurityException.class, m::listItems);
		assertEquals(0, failures());
		assertArrayEquals(before, headerBytes());
		assertEquals(Collections.singletonList(ALIAS), aliases());

		assertEquals("opens", confirm(m, CHOSEN, CHOSEN));

		assertEquals(Arrays.asList("typed", "zeros " + CHOSEN.length,
				"typed"), derivations);
		assertTrue(m.isUnlocked());
		assertEquals("Testnote kept", note(m));
		assertEquals(0, failures());
		assertFalse(Arrays.equals(before, headerBytes()));
		VaultHeader after = header();
		assertEquals("version 2, 256 MiB t=3", label(after));
		assertEquals(VaultKeystore.SLOT_A, after.keySlot);
		assertArrayEquals(BIOMETRIC_SALT, after.biometricTokenSalt);
		assertEquals(created, after.createdTimestamp);
		assertEquals(2, aliases().size());
		assertFalse(aliases().contains(ALIAS));
		m.lockVault();

		VaultManager later = process();
		assertEquals("opens", attempt(later, CHOSEN));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals("Testnote kept", note(later));
		assertTrue(check(CHOSEN));
		assertEquals("refused", attempt(new char[0]));
		assertEquals("refused", attempt(new char[CHOSEN.length]));
		assertEquals("refused", attempt(SAME_LENGTH));
		assertEquals("refused", attempt(LONGER));
		assertEquals("opens", attempt(CHOSEN));
	}

	@Test
	public void untilSealedAgainTheEmptyFormOpensForAnyPasswordOfTheMinimumLength()
			throws Exception {
		assertEquals(VaultManager.MIN_PASSWORD_LENGTH, MINIMUM.length);
		char[][] typed = {CHOSEN, SAME_LENGTH, LONGER, MINIMUM};
		for (char[] password : typed) {
			startOver();
			sealUnderTheEmptyPassword();
			assertEquals("opens", twice(password));
			assertEquals(Arrays.asList("typed", "empty", "typed"),
					derivations);
			assertEquals("opens", attempt(password));
			assertEquals(Collections.singletonList("typed"), derivations);
			assertEquals("refused", attempt(new char[0]));
		}
	}

	@Test
	public void untilSealedAgainTheZeroFormOpensOnlyForAPasswordOfItsLength()
			throws Exception {
		sealUnderZeros(CHOSEN.length);
		byte[] before = headerBytes();
		assertEquals("refused", attempt(LONGER));
		assertEquals(Arrays.asList("typed", "zeros " + LONGER.length),
				derivations);
		assertEquals(1, failures());
		assertEquals("refused", attempt(MINIMUM));
		assertEquals(Arrays.asList("typed", "zeros " + MINIMUM.length),
				derivations);
		assertArrayEquals(before, headerBytes());

		assertEquals("opens", twice(SAME_LENGTH));
		assertEquals("opens", attempt(SAME_LENGTH));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals("refused", attempt(CHOSEN));
	}

	@Test
	public void aKeystoreFactorHeaderNeverTriesTheZeroForm() throws Exception {
		sealVersionTwo(new char[CHOSEN.length], C);
		assertEquals(VaultHeader.VERSION_KEYSTORE_FACTOR, header().version);
		byte[] before = headerBytes();
		List<String> keys = aliases();

		assertEquals("refused", attempt(CHOSEN));

		assertEquals(Arrays.asList("typed", "empty"), derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());
		assertEquals(keys, aliases());
	}

	@Test
	public void anOlderHeaderNeverTriesTheEmptyForm() throws Exception {
		sealVersionOne(new char[0]);
		assertTrue(header().version < VaultHeader.VERSION_KEYSTORE_FACTOR);
		byte[] before = headerBytes();
		List<String> keys = aliases();

		assertEquals("refused", attempt(CHOSEN));

		assertEquals(Arrays.asList("typed", "zeros " + CHOSEN.length),
				derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());
		assertEquals(keys, aliases());
	}

	@Test
	public void aRefusedUnlockDerivesTwoKeysWhateverTheHeader()
			throws Exception {
		sealUnderTheEmptyPassword();
		VaultManager m = process();
		assertEquals("opens", twice(m, CHOSEN));
		m.lockVault();
		assertEquals("refused", attempt(LONGER));
		assertEquals(2, derivations.size());

		startOver();
		sealVersionOne(PASSWORD);
		assertEquals("refused", attempt(LONGER));
		assertEquals(2, derivations.size());
		assertEquals("opens", attempt(PASSWORD));
	}

	@Test
	public void aPasswordShorterThanTheMinimumNeverTriesTheClearedForms()
			throws Exception {
		assertEquals(VaultManager.MIN_PASSWORD_LENGTH - 1, SHORT.length);
		sealUnderTheEmptyPassword();
		byte[] before = headerBytes();
		assertEquals("refused", attempt(SHORT));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());

		startOver();
		sealUnderZeros(SHORT.length);
		before = headerBytes();
		assertEquals("refused", attempt(SHORT));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void aPasswordThatIsEmptyInItsNormalFormNeverTriesTheZeroForm()
			throws Exception {
		char[] invisible = new char[CHOSEN.length];
		Arrays.fill(invisible, (char) 0x200B);
		sealUnderZeros(invisible.length);
		byte[] before = headerBytes();

		assertEquals("refused", attempt(invisible));

		assertEquals(Arrays.asList("empty", "typed"), derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void anUnaffectedVaultOpensForNothingButItsPassword()
			throws Exception {
		VaultManager first = process();
		first.createVault(PASSWORD.clone());
		first.addItem(VaultItem.ItemType.NOTE, "Testnote",
				"kept".getBytes(StandardCharsets.UTF_8));
		first.lockVault();
		byte[] before = headerBytes();
		List<String> keys = aliases();

		assertEquals("refused", attempt(LONGER));
		assertEquals(Arrays.asList("typed", "empty"), derivations);
		assertEquals(1, failures());
		assertEquals("refused", attempt(new char[0]));
		assertEquals(Collections.singletonList("empty"), derivations);
		assertEquals(2, failures());
		assertEquals("refused", attempt(new char[PASSWORD.length]));
		assertEquals(Arrays.asList("empty", "zeros " + PASSWORD.length),
				derivations);
		assertEquals(3, failures());
		assertEquals("refused", attempt(SHORT));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(4, failures());
		assertFalse(check(LONGER));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(5, failures());
		assertArrayEquals(before, headerBytes());
		assertEquals(keys, aliases());

		VaultManager m = process();
		assertEquals("opens", attempt(m, PASSWORD));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(0, failures());
		assertEquals("Testnote kept", note(m));
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void aVaultSealedUnderAShortNormalFormKeepsOpening()
			throws Exception {
		char[] typed = "pass\uD83D\uDE00\uD83D\uDE00".toCharArray();
		assertEquals(VaultManager.MIN_PASSWORD_LENGTH, typed.length);
		char[] normal = PasswordNormalizer.normalize(typed);
		assertEquals(4, normal.length);
		sealVersionTwo(normal, C);
		byte[] before = headerBytes();
		VaultManager m = process();

		assertEquals("opens", attempt(m, typed));

		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals("Testnote kept", note(m));
		assertArrayEquals(before, headerBytes());
		assertTrue(check(typed));
		assertThrows(IllegalArgumentException.class, () ->
				m.changePassword(typed.clone(), typed.clone()));
		assertArrayEquals(before, headerBytes());
		nextAttempt();

		m.changePassword(typed.clone(), NEW_PASSWORD.clone());

		assertEquals("Testnote kept", note(m));
		VaultManager later = process();
		assertEquals("opens", attempt(later, NEW_PASSWORD));
		assertEquals("Testnote kept", note(later));
	}

	@Test
	public void checkingAPasswordNeverUsesTheClearedForms() throws Exception {
		sealUnderTheEmptyPassword();
		byte[] before = headerBytes();
		assertFalse(check(CHOSEN));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(1, failures());
		assertFalse(check(LONGER));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertArrayEquals(before, headerBytes());

		startOver();
		sealUnderZeros(CHOSEN.length);
		before = headerBytes();
		assertFalse(check(CHOSEN));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(1, failures());
		assertFalse(check(SAME_LENGTH));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void aPasswordChangeWorksOnAVaultThatWasSealedAgain()
			throws Exception {
		sealUnderTheEmptyPassword();
		VaultManager m = process();
		assertEquals("opens", twice(m, CHOSEN));
		nextAttempt();
		assertThrows(SecurityException.class, () ->
				m.changePassword(SAME_LENGTH.clone(), NEW_PASSWORD.clone()));
		assertEquals(Collections.singletonList("typed"), derivations);
		nextAttempt();

		m.changePassword(CHOSEN.clone(), NEW_PASSWORD.clone());

		assertEquals("Testnote kept", note(m));
		assertArrayEquals(BIOMETRIC_SALT, header().biometricTokenSalt);
		assertEquals("refused", attempt(CHOSEN));
		VaultManager later = process();
		assertEquals("opens", attempt(later, NEW_PASSWORD));
		assertEquals("Testnote kept", note(later));
	}

	@Test
	public void aSealThatFailsLeavesTheVaultClosedAndUnchanged()
			throws Exception {
		for (int form = 0; form < 2; form++) {
			startOver();
			if (form == 0) sealUnderTheEmptyPassword();
			else sealUnderZeros(CHOSEN.length);
			byte[] before = headerBytes();
			List<String> keys = aliases();
			VaultManager m = process();
			assertEquals("asks again", attempt(m, CHOSEN));
			sealFails = true;

			assertEquals("not confirmed on storage",
					confirm(m, CHOSEN, CHOSEN));

			assertFalse(m.isUnlocked());
			assertThrows(SecurityException.class, m::listItems);
			assertEquals(1, failures());
			assertArrayEquals(before, headerBytes());
			assertEquals(keys, aliases());

			sealFails = false;
			assertEquals("opens", twice(m, CHOSEN));
			assertEquals("Testnote kept", note(m));
			assertEquals("version 2, 256 MiB t=3", label(header()));
		}
	}

	@Test
	public void aVaultIsNotOpenedWhenTheNewHeaderIsNotConfirmedOnDisk()
			throws Exception {
		sealUnderTheEmptyPassword();
		VaultManager m = process();
		assertEquals("asks again", attempt(m, LONGER));
		syncFailsAtSeal = true;

		assertEquals("not confirmed on storage",
				confirm(m, LONGER, LONGER));

		assertFalse(m.isUnlocked());
		assertThrows(SecurityException.class, m::listItems);
		assertEquals(1, failures());
		syncFailsAtSeal = false;
		assertEquals("refused", attempt(CHOSEN));
		assertEquals("refused", attempt(new char[0]));
		assertEquals("opens", attempt(m, LONGER));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals(0, failures());
		m.lockVault();
		VaultManager later = process();
		assertEquals("opens", attempt(later, LONGER));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertEquals("Testnote kept", note(later));
		assertEquals(2, aliases().size());
	}

	@Test
	public void theNewSealNeverWeakensTheKeyDerivation() throws Exception {
		sealUnderTheEmptyPassword(C);
		assertEquals("opens", twice(phone(A), CHOSEN));
		assertEquals("version 2, 1024 MiB t=1", label(header()));

		startOver();
		sealUnderTheEmptyPassword(A);
		assertEquals("opens", twice(phone(C), CHOSEN));
		assertEquals("version 2, 1024 MiB t=1", label(header()));
		assertEquals("opens", attempt(phone(C), CHOSEN));
	}

	private void sealCleared(int form) throws Exception {
		startOver();
		if (form == 0) sealUnderTheEmptyPassword();
		else sealUnderZeros(CHOSEN.length);
	}

	@Test
	public void aTypoOnTheFirstEntryWritesNothingAndTheVaultAsksAgain()
			throws Exception {
		assertEquals(CHOSEN.length, SAME_LENGTH.length);
		for (int form = 0; form < 2; form++) {
			sealCleared(form);
			byte[] before = headerBytes();
			List<String> keys = aliases();
			VaultManager m = process();
			assertEquals("asks again", attempt(m, SAME_LENGTH));

			assertEquals("entries differ", confirm(m, SAME_LENGTH, CHOSEN));

			assertEquals(Collections.emptyList(), derivations);
			assertFalse(m.isUnlocked());
			assertThrows(SecurityException.class, m::listItems);
			assertEquals(0, failures());
			assertArrayEquals(before, headerBytes());
			assertEquals(keys, aliases());

			assertEquals("asks again", attempt(m, CHOSEN));
			assertArrayEquals(before, headerBytes());
			assertEquals("opens", confirm(m, CHOSEN, CHOSEN));
			assertEquals("Testnote kept", note(m));
			assertEquals("refused", attempt(SAME_LENGTH));
			assertEquals("opens", attempt(CHOSEN));
		}
	}

	@Test
	public void aSecondEntryThatDiffersFromTheFirstWritesNothing()
			throws Exception {
		for (int form = 0; form < 2; form++) {
			sealCleared(form);
			byte[] before = headerBytes();
			List<String> keys = aliases();
			VaultManager m = process();
			assertEquals("asks again", attempt(m, CHOSEN));

			assertEquals("entries differ", confirm(m, CHOSEN, SAME_LENGTH));

			assertEquals(Collections.emptyList(), derivations);
			assertFalse(m.isUnlocked());
			assertEquals(0, failures());
			assertArrayEquals(before, headerBytes());
			assertEquals(keys, aliases());
			assertEquals("asks again", attempt(m, SAME_LENGTH));
			assertEquals("asks again", attempt(m, CHOSEN));
			assertArrayEquals(before, headerBytes());
		}
	}

	@Test
	public void theManagerKeepsNothingOfTheFirstEntry() throws Exception {
		for (int form = 0; form < 2; form++) {
			sealCleared(form);
			byte[] before = headerBytes();
			VaultManager m = process();
			assertEquals("asks again", attempt(m, CHOSEN));
			m.lockVault();

			assertEquals("asks again", attempt(m, CHOSEN));
			assertEquals("asks again", attempt(m, CHOSEN));

			assertFalse(m.isUnlocked());
			assertThrows(SecurityException.class, m::listItems);
			assertArrayEquals(before, headerBytes());
			assertEquals(0, failures());
		}
	}

	@Test
	public void aProcessThatDiesBetweenTheEntriesLeavesTheVaultRecoverable()
			throws Exception {
		for (int form = 0; form < 2; form++) {
			sealCleared(form);
			byte[] before = headerBytes();
			List<String> keys = aliases();
			assertEquals("asks again", attempt(process(), SAME_LENGTH));

			VaultManager next = process();
			assertArrayEquals(before, headerBytes());
			assertEquals(keys, aliases());
			assertEquals("asks again", attempt(next, CHOSEN));
			assertEquals("opens", confirm(next, CHOSEN, CHOSEN));
			assertEquals("Testnote kept", note(next));
			assertEquals("refused", attempt(SAME_LENGTH));
			assertEquals("opens", attempt(CHOSEN));
		}
	}

	@Test
	public void theConfirmStepGivesAnOrdinaryVaultNothingExtra()
			throws Exception {
		VaultManager first = process();
		first.createVault(PASSWORD.clone());
		first.addItem(VaultItem.ItemType.NOTE, "Testnote",
				"kept".getBytes(StandardCharsets.UTF_8));
		first.lockVault();
		byte[] before = headerBytes();
		VaultManager m = process();

		assertEquals("entries differ", confirm(m, LONGER, PASSWORD));
		assertEquals(Collections.emptyList(), derivations);
		assertFalse(m.isUnlocked());
		assertEquals(0, failures());

		assertEquals("refused", confirm(m, LONGER, LONGER));
		assertEquals(Arrays.asList("typed", "empty"), derivations);
		assertEquals(1, failures());
		assertFalse(m.isUnlocked());
		assertArrayEquals(before, headerBytes());

		assertEquals("opens", confirm(m, PASSWORD, PASSWORD));
		assertEquals(Collections.singletonList("typed"), derivations);
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void bothEntriesWaitForTheThrottle() throws Exception {
		sealUnderZeros(CHOSEN.length);
		byte[] before = headerBytes();
		VaultManager m = process();
		assertEquals("refused", attempt(m, LONGER));
		assertEquals(1, failures());
		derivations.clear();

		SecurityException first = assertThrows(SecurityException.class,
				() -> m.unlock(CHOSEN.clone()));
		SecurityException second = assertThrows(SecurityException.class,
				() -> m.confirmAndUnlock(CHOSEN.clone(), CHOSEN.clone()));

		assertTrue(first.getMessage()
				.startsWith(VaultManager.TOO_MANY_ATTEMPTS));
		assertTrue(second.getMessage()
				.startsWith(VaultManager.TOO_MANY_ATTEMPTS));
		assertEquals(Collections.emptyList(), derivations);
		assertEquals(1, failures());
		assertArrayEquals(before, headerBytes());
	}

	@Test
	public void recoveryStillTakesAPasswordThatIsShortInItsNormalForm()
			throws Exception {
		char[] typed = "pass\uD83D\uDE00\uD83D\uDE00".toCharArray();
		assertEquals(VaultManager.MIN_PASSWORD_LENGTH, typed.length);
		assertEquals(4, PasswordNormalizer.normalize(typed).length);
		assertFalse(VaultManager.acceptsNewPassword(typed.clone()));
		sealUnderTheEmptyPassword();
		VaultManager m = process();

		assertEquals("opens", twice(m, typed));

		assertEquals("Testnote kept", note(m));
		assertEquals("version 2, 256 MiB t=3", label(header()));
		assertEquals("opens", attempt(typed));
		assertEquals("refused", attempt(CHOSEN));
		assertEquals("refused", attempt(new char[0]));
	}

	@Test
	public void theEntriesAreComparedInTheFormTheSealUses() {
		char[] typed = "Vaultnew67890".toCharArray();
		char[] withAnEmoji = "Vaultnew\uD83D\uDE0067890".toCharArray();
		assertArrayEquals(PasswordNormalizer.normalize(typed),
				PasswordNormalizer.normalize(withAnEmoji));
		assertTrue(VaultManager.sameNormalForm(typed, withAnEmoji));
		assertTrue(VaultManager.sameNormalForm(typed, typed.clone()));
		assertFalse(VaultManager.sameNormalForm(typed, SAME_LENGTH));
		assertFalse(VaultManager.sameNormalForm(typed, LONGER));
		assertFalse(VaultManager.sameNormalForm(typed,
				Arrays.copyOf(typed, typed.length - 1)));
		assertFalse(VaultManager.sameNormalForm(typed, new char[0]));
		assertArrayEquals("Vaultnew67890".toCharArray(), typed);
	}
}
