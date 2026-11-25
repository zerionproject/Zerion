package com.professor.zerion.android.vault;

import android.app.Application;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.net.TorSocksGate;
import com.professor.zerion.android.vault.storage.VaultLocation;
import com.professor.zerion.android.vault.ui.DocumentSaveResult;
import com.professor.zerion.android.vault.ui.NoteSaveResult;
import com.professor.zerion.android.vault.ui.VaultViewModel;
import com.professor.zerion.android.vault.wallet.WalletStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.PasswordNormalizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class VaultNewPasswordTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ALIAS = "zerion_vault_master_key_test";
	private static final char[] PASSWORD = "vault password 4&Rt".toCharArray();
	private static final char[] NEW_PASSWORD =
			"other vault password 6*Ux".toCharArray();
	private static final char[] DOCUMENT_PASSWORD =
			"open sesame".toCharArray();
	private static final char[] WIPED = new char[12];
	private static final char[] WITH_A_ZERO_INSIDE =
			{'n', 'e', 'w', ' ', 'p', 'a', 's', 's', 0, '5', '%', 'T'};
	private static final char[] ONLY_PICTURES = ("\uD83D\uDE00\uD83D\uDD12"
			+ "\uD83D\uDE00\uD83D\uDD12\uD83D\uDE00\uD83D\uDD12\uD83D\uDE00"
			+ "\uD83D\uDD12").toCharArray();
	private static final char[] OTHER_PICTURES = ("\uD83D\uDD12\uD83D\uDD11"
			+ "\uD83D\uDD12\uD83D\uDD11\uD83D\uDD12\uD83D\uDD11\uD83D\uDD12"
			+ "\uD83D\uDD11").toCharArray();
	private static final char[] ONLY_INVISIBLE = ("\u200B\u200B\u200B\u200B"
			+ "\u200B\u200B\u200B\u200B\u200B\u200B\u200B\u200B")
			.toCharArray();
	private static final char[] FOUR_AND_PICTURES =
			"pass\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00".toCharArray();
	private static final char[] SEVEN_AND_INVISIBLE =
			"Seven7&\u200B\u200B\u200B".toCharArray();
	private static final char[] EIGHT_AND_PICTURES =
			"Eight8&x\uD83D\uDE00\uD83D\uDD12".toCharArray();
	private static final char[] EIGHT = "Eight8&x".toCharArray();
	private static final char[][] TOO_SHORT_IN_NORMAL_FORM = {ONLY_PICTURES,
			ONLY_INVISIBLE, FOUR_AND_PICTURES, SEVEN_AND_INVISIBLE};

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private final Application app = RuntimeEnvironment.getApplication();
	private File dir;

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
		dir = Files.createTempDirectory("vault-new-password").toFile();
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

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
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

	private VaultManager process() {
		return new VaultManager(app, location(),
				VaultNewPasswordTest::fakeDerive);
	}

	private VaultManager created() throws Exception {
		VaultManager m = process();
		m.createVault(PASSWORD.clone());
		return m;
	}

	private VaultViewModel screens(VaultManager m) {
		TorSocksGate gate = mock(TorSocksGate.class);
		when(gate.port()).thenReturn(1);
		return new VaultViewModel(app, m, Runnable::run, new WalletStore(m),
				gate);
	}

	private String unlock(char[] password) {
		try {
			return process().unlockVault(password.clone())
					? "opens" : "refused";
		} catch (Exception e) {
			return "refused";
		}
	}

	private byte[] header() throws Exception {
		return Files.readAllBytes(
				new File(vaultDir(), "vault.header").toPath());
	}

	private int storedItems() {
		String[] stored = new File(vaultDir(), "items").list();
		return stored == null ? 0 : stored.length;
	}

	@Test
	public void aPasswordChangedThroughOneArrayIsKeptAndOpensTheVault()
			throws Exception {
		VaultManager m = created();
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));
		VaultViewModel vm = screens(m);
		char[] both = PASSWORD.clone();

		vm.changePassword(both, both);

		assertArrayEquals(new char[PASSWORD.length], both);
		assertNull(vm.getErrorMessage().getValue());
		assertEquals(app.getString(R.string.vault_settings_password_changed),
				vm.getSuccessMessage().getValue());
		VaultManager after = process();
		assertTrue("the vault no longer opens with its password",
				after.unlockVault(PASSWORD.clone()));
		assertArrayEquals(bytes("kept"),
				after.getItemContent(after.listItems().get(0).id));
		assertEquals("refused", unlock(new char[PASSWORD.length]));
	}

	@Test
	public void aVaultIsNotReKeyedUnderAPasswordThatCannotBeTyped()
			throws Exception {
		VaultManager m = created();
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));
		byte[] before = header();

		assertThrows(IllegalArgumentException.class, () ->
				m.changePassword(PASSWORD.clone(), WIPED.clone()));
		assertThrows(IllegalArgumentException.class, () ->
				m.changePassword(PASSWORD.clone(),
						WITH_A_ZERO_INSIDE.clone()));

		assertArrayEquals("the vault was given another key", before,
				header());
		assertEquals("opens", unlock(PASSWORD));
		assertArrayEquals(bytes("kept"),
				m.getItemContent(m.listItems().get(0).id));
	}

	@Test
	public void aRefusedNewVaultPasswordIsReportedWithTheRule()
			throws Exception {
		VaultManager m = created();
		byte[] before = header();
		VaultViewModel vm = screens(m);

		vm.changePassword(PASSWORD.clone(), WIPED.clone());

		assertEquals(app.getString(R.string.vault_password_rule),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertArrayEquals(before, header());
		assertEquals("opens", unlock(PASSWORD));
	}

	@Test
	public void aVaultIsNotCreatedUnderAPasswordThatCannotBeTyped()
			throws Exception {
		VaultManager m = process();

		assertThrows(IllegalArgumentException.class, () ->
				m.createVault(WIPED.clone()));
		assertThrows(IllegalArgumentException.class, () ->
				m.createVault(WITH_A_ZERO_INSIDE.clone()));

		assertFalse(m.vaultExists());
		assertFalse(m.isUnlocked());
		assertFalse(new File(vaultDir(), "vault.header").exists());

		VaultViewModel vm = screens(m);
		vm.createVault(WIPED.clone(), WIPED.clone());

		assertEquals(app.getString(R.string.vault_password_rule),
				vm.getErrorMessage().getValue());
		assertFalse(m.vaultExists());
	}

	@Test
	public void aDocumentIsNotSealedUnderAPasswordThatCannotBeTyped()
			throws Exception {
		VaultManager m = created();

		assertThrows(IllegalArgumentException.class, () ->
				m.addMediaItemWithPassword(VaultItem.ItemType.DOCUMENT,
						"notes.txt", bytes("first draft"), "text/plain",
						WIPED.clone()));
		assertThrows(IllegalArgumentException.class, () ->
				m.addItemWithPassword(VaultItem.ItemType.DOCUMENT,
						"notes.txt", bytes("first draft"),
						WITH_A_ZERO_INSIDE.clone()));

		assertTrue(m.listItems().isEmpty());
		assertEquals(0, storedItems());

		VaultViewModel vm = screens(m);
		long request = vm.newDocumentSaveRequest();
		vm.addDocumentWithPassword(request, "notes.txt",
				bytes("first draft"), WIPED.clone());

		DocumentSaveResult result = vm.takeDocumentSaveResult(request);
		assertNotNull(result);
		assertEquals(DocumentSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(app.getString(R.string.vault_msg_add_document_failed),
				result.message);
		assertTrue(m.listItems().isEmpty());
		assertEquals(0, storedItems());
	}

	@Test
	public void aNoteIsNotStoredUnderAPasswordThatCannotBeTyped()
			throws Exception {
		VaultManager m = created();
		VaultViewModel vm = screens(m);
		long request = vm.newNoteSaveRequest();

		vm.saveNoteWithPassword(request, "Diary", "dear diary",
				WIPED.clone(), null);

		NoteSaveResult result = vm.takeNoteSaveResult(request,
				(title, content, passwordProtected) -> {
				});
		assertNotNull(result);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(app.getString(R.string.vault_msg_save_note_failed),
				result.message);
		assertTrue(m.listItems().isEmpty());
		assertEquals(0, storedItems());
	}

	@Test
	public void theRefusedPasswordsPassTheLengthCheckOfTheScreens() {
		for (char[] typed : TOO_SHORT_IN_NORMAL_FORM) {
			assertTrue(typed.length >= VaultManager.MIN_PASSWORD_LENGTH);
			assertTrue(PasswordNormalizer.normalize(typed).length
					< VaultManager.MIN_PASSWORD_LENGTH);
		}
		assertEquals(0, PasswordNormalizer.normalize(ONLY_PICTURES).length);
		assertEquals(0, PasswordNormalizer.normalize(ONLY_INVISIBLE).length);
		assertArrayEquals(EIGHT,
				PasswordNormalizer.normalize(EIGHT_AND_PICTURES));
	}

	@Test
	public void aVaultIsNotCreatedUnderAPasswordTooShortInItsNormalForm()
			throws Exception {
		for (char[] typed : TOO_SHORT_IN_NORMAL_FORM) {
			VaultManager m = process();

			assertThrows(IllegalArgumentException.class, () ->
					m.createVault(typed.clone()));

			assertFalse(m.vaultExists());
			assertFalse(m.isUnlocked());
			assertFalse(new File(vaultDir(), "vault.header").exists());

			VaultViewModel vm = screens(m);
			vm.createVault(typed.clone(), typed.clone());

			assertEquals(app.getString(R.string.vault_password_rule),
					vm.getErrorMessage().getValue());
			assertFalse(m.vaultExists());
		}
	}

	@Test
	public void theScreensAskTheRuleTheManagerApplies() throws Exception {
		char[][] refused = {ONLY_PICTURES, ONLY_INVISIBLE, FOUR_AND_PICTURES,
				SEVEN_AND_INVISIBLE, WIPED, WITH_A_ZERO_INSIDE, new char[0],
				"Seven7&".toCharArray()};
		for (char[] typed : refused) {
			char[] asked = typed.clone();
			assertFalse(VaultManager.acceptsNewPassword(asked));
			assertArrayEquals(typed, asked);
			assertThrows(IllegalArgumentException.class, () ->
					process().createVault(typed.clone()));
		}
		char[][] accepted = {EIGHT, EIGHT_AND_PICTURES, PASSWORD,
				NEW_PASSWORD};
		for (char[] typed : accepted) {
			char[] asked = typed.clone();
			assertTrue(VaultManager.acceptsNewPassword(asked));
			assertArrayEquals(typed, asked);
		}
		VaultManager m = process();
		m.createVault(EIGHT_AND_PICTURES.clone());
		assertTrue(m.vaultExists());
	}

	@Test
	public void theNewPasswordIsTakenBeforeTheSlowCheckOfTheOldOne()
			throws Exception {
		char[] handedOver = NEW_PASSWORD.clone();
		boolean[] changing = {false};
		int[] wipedAtDerivation = {0};
		int[] derivations = {0};
		VaultManager m = new VaultManager(app, location(),
				(password, salt, params) -> {
					if (changing[0]) {
						derivations[0]++;
						if (wipedAtDerivation[0] == 0) {
							java.util.Arrays.fill(handedOver, '\0');
							wipedAtDerivation[0] = derivations[0];
						}
					}
					return fakeDerive(password, salt, params);
				});
		m.createVault(PASSWORD.clone());
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));
		changing[0] = true;

		m.changePassword(PASSWORD.clone(), handedOver);

		changing[0] = false;
		assertEquals("the array was wiped while the old password was checked",
				1, wipedAtDerivation[0]);
		assertArrayEquals(new char[NEW_PASSWORD.length], handedOver);
		assertEquals("opens", unlock(NEW_PASSWORD));
		char[][] others = {new char[NEW_PASSWORD.length], new char[0],
				PASSWORD};
		for (char[] other : others) {
			org.robolectric.shadows.ShadowSystemClock.advanceBy(
					java.time.Duration.ofSeconds(30));
			assertFalse(process().unlockVault(other.clone()));
		}
		org.robolectric.shadows.ShadowSystemClock.advanceBy(
				java.time.Duration.ofSeconds(30));
		VaultManager after = process();
		assertTrue(after.unlockVault(NEW_PASSWORD.clone()));
		assertArrayEquals(bytes("kept"),
				after.getItemContent(after.listItems().get(0).id));
	}

	@Test
	public void aVaultIsNotReKeyedUnderAPasswordTooShortInItsNormalForm()
			throws Exception {
		VaultManager m = created();
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));
		byte[] before = header();

		for (char[] typed : TOO_SHORT_IN_NORMAL_FORM) {
			assertThrows(IllegalArgumentException.class, () ->
					m.changePassword(PASSWORD.clone(), typed.clone()));

			VaultViewModel vm = screens(m);
			vm.changePassword(PASSWORD.clone(), typed.clone());

			assertEquals(app.getString(R.string.vault_password_rule),
					vm.getErrorMessage().getValue());
			assertNull(vm.getSuccessMessage().getValue());
		}

		assertArrayEquals("the vault was given another key", before,
				header());
		assertEquals("opens", unlock(PASSWORD));
		assertArrayEquals(bytes("kept"),
				m.getItemContent(m.listItems().get(0).id));
		assertEquals("refused", unlock(new char[0]));
	}

	@Test
	public void aPasswordLongEnoughInItsNormalFormIsSetAndOpens()
			throws Exception {
		VaultManager m = process();
		m.createVault(EIGHT_AND_PICTURES.clone());
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));

		assertEquals("opens", unlock(EIGHT_AND_PICTURES));
		assertEquals("opens", unlock(EIGHT));

		m.changePassword(EIGHT_AND_PICTURES.clone(), PASSWORD.clone());
		m.changePassword(PASSWORD.clone(), EIGHT_AND_PICTURES.clone());

		VaultManager after = process();
		assertTrue(after.unlockVault(EIGHT_AND_PICTURES.clone()));
		assertArrayEquals(bytes("kept"),
				after.getItemContent(after.listItems().get(0).id));
		assertEquals("refused", unlock(PASSWORD));
	}

	@Test
	public void anExportIsNotSealedUnderAPasswordThatIsNothingInItsNormalForm()
			throws Exception {
		VaultManager m = created();
		m.addItem(VaultItem.ItemType.NOTE, "note", bytes("kept"));

		assertThrows(IllegalArgumentException.class, () ->
				m.exportVault(ONLY_PICTURES.clone()));
		assertThrows(IllegalArgumentException.class, () ->
				m.exportVault(ONLY_INVISIBLE.clone()));
		assertThrows(IllegalArgumentException.class, () ->
				m.exportVault(WIPED.clone()));

		VaultViewModel vm = screens(m);
		String[] reported = new String[1];
		byte[][] written = new byte[1][];
		vm.exportVault(ONLY_PICTURES.clone(),
				new VaultViewModel.ExportCallback() {
					@Override
					public void onExportSuccess(byte[] data) {
						written[0] = data;
					}

					@Override
					public void onExportError(String error) {
						reported[0] = error;
					}
				});

		assertNull(written[0]);
		assertEquals(app.getString(
				R.string.vault_document_export_generic_failed), reported[0]);
		assertNotNull(m.exportVault(FOUR_AND_PICTURES.clone()));
	}

	@Test
	public void anItemPasswordIsUsedAsTypedAndNotInANormalForm()
			throws Exception {
		VaultManager m = created();
		VaultItem document = m.addItemWithPassword(
				VaultItem.ItemType.DOCUMENT, "notes.txt",
				bytes("first draft"), ONLY_PICTURES.clone());

		assertArrayEquals(bytes("first draft"),
				m.getItemContentWithPassword(document.id,
						ONLY_PICTURES.clone()));
		assertThrows(Exception.class, () ->
				m.getItemContentWithPassword(document.id,
						OTHER_PICTURES.clone()));
	}

	@Test
	public void ordinaryPasswordsSealAndOpenAsBefore() throws Exception {
		VaultManager m = created();
		VaultItem document = m.addMediaItemWithPassword(
				VaultItem.ItemType.DOCUMENT, "notes.txt",
				bytes("first draft"), "text/plain",
				DOCUMENT_PASSWORD.clone());

		m.changePassword(PASSWORD.clone(), NEW_PASSWORD.clone());

		VaultManager after = process();
		assertTrue(after.unlockVault(NEW_PASSWORD.clone()));
		assertArrayEquals(bytes("first draft"),
				after.getItemContentWithPassword(document.id,
						DOCUMENT_PASSWORD.clone()));
	}

	@Test
	public void openingAVaultNeverLooksForSuchACharacter() throws Exception {
		VaultManager m = created();
		char[] typed = {'v', 'a', 'u', 'l', 't', 0, ' ', 'p', 'a', 's', 's',
				'w', 'o', 'r', 'd', ' ', '4', '&', 'R', 't'};

		assertEquals("opens", unlock(typed));
		assertTrue(m.verifyMasterPassword(typed.clone()));
	}
}
