package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileRecoveryTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";
	private static final String WRONG = "not a password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileRecoveryTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private File database(String id) throws Exception {
		File db = new File(device.profileDir(id), "db/db.sqlite");
		assertTrue(db.getParentFile().isDirectory()
				|| db.getParentFile().mkdirs());
		Files.write(db.toPath(), id.getBytes("UTF-8"));
		return db;
	}

	private File eraseRequest() {
		return new File(testDir, "erase.requested");
	}

	private AndroidAccountManager processWithHint(String hint) {
		ProfileManager profiles = device.newProfileManager();
		profiles.writeLastActiveProfileId(hint);
		AndroidAccountManager accountManager =
				device.newAccountManager(profiles);
		accountManager.setErasePolicy(failures -> failures >= 1);
		return accountManager;
	}

	private static String signIn(AndroidAccountManager accountManager,
			String password) {
		try {
			accountManager.signIn(password.toCharArray());
			return "opens " + accountManager.getActiveProfileId();
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	private String nothingErased(String... ids) {
		if (eraseRequest().exists()) return "erase requested";
		for (String id : ids) {
			if (!device.keyFile(id).exists()) return "key of " + id + " gone";
		}
		return "nothing erased";
	}

	@Test
	public void aProfileWhoseKeystoreKeyFailsLocksNoOtherProfileOut()
			throws Exception {
		device.addProfile("decoy", DECOY);
		device.addProfile("hidden", HIDDEN);
		device.loseKeystoreKeyOf("decoy");

		AndroidAccountManager tried = processWithHint("decoy");
		String failingFirst = signIn(tried, HIDDEN);
		AndroidAccountManager triedLast = processWithHint("hidden");
		String failingLast = signIn(triedLast, HIDDEN);

		assertEquals("tried first: opens hidden, tried last: opens hidden,"
						+ " nothing erased",
				"tried first: " + failingFirst + ", tried last: "
						+ failingLast + ", " + nothingErased("decoy",
						"hidden"));
	}

	@Test
	public void aFailingKeystoreKeyIsReportedAndNeverCountsAsAWrongPassword()
			throws Exception {
		device.addProfile("decoy", DECOY);
		device.addProfile("hidden", HIDDEN);
		device.loseKeystoreKeyOf("hidden");
		AndroidAccountManager accountManager = processWithHint("decoy");

		String wrong = signIn(accountManager, WRONG);
		String ownPassword = signIn(accountManager, HIDDEN);

		assertEquals(KEY_STRENGTHENER_ERROR.name()
						+ ", " + KEY_STRENGTHENER_ERROR.name()
						+ ", 0 failures, nothing erased",
				wrong + ", " + ownPassword + ", "
						+ accountManager.failedSignInAttempts()
						+ " failures, " + nothingErased("decoy", "hidden"));
		assertEquals("opens decoy", signIn(accountManager, DECOY));
	}

	@Test
	public void aKeylessProfileBesideKeyedOnesIsTheRestOfADeletion()
			throws Exception {
		device.addProfile("decoy", DECOY);
		device.addProfile("hidden", HIDDEN);
		File decoyDb = database("decoy");
		File hiddenDb = database("hidden");
		File deletedDb = database("deleted");
		byte[] hiddenKey = Files.readAllBytes(device.keyFile("hidden")
				.toPath());

		AndroidAccountManager accountManager = processWithHint("decoy");

		assertFalse(deletedDb.exists());
		assertFalse(device.profileDir("deleted").exists());
		assertTrue(decoyDb.exists());
		assertTrue(hiddenDb.exists());
		assertTrue(Arrays.equals(hiddenKey,
				Files.readAllBytes(device.keyFile("hidden").toPath())));
		assertEquals("opens hidden", signIn(accountManager, HIDDEN));
	}

	@Test
	public void switchingProfilesLeavesTheOtherProfileAsItWas()
			throws Exception {
		device.addProfile("decoy", DECOY);
		device.addProfile("hidden", HIDDEN);
		File decoyDb = database("decoy");
		File hiddenDb = database("hidden");
		byte[] decoyKey = Files.readAllBytes(device.keyFile("decoy")
				.toPath());
		byte[] hiddenKey = Files.readAllBytes(device.keyFile("hidden")
				.toPath());

		String first = signIn(processWithHint("decoy"), DECOY);
		String second = signIn(device.newAccountManager(), HIDDEN);
		String third = signIn(device.newAccountManager(), DECOY);

		assertEquals("opens decoy, opens hidden, opens decoy",
				first + ", " + second + ", " + third);
		assertTrue(Arrays.equals(decoyKey,
				Files.readAllBytes(device.keyFile("decoy").toPath())));
		assertTrue(Arrays.equals(hiddenKey,
				Files.readAllBytes(device.keyFile("hidden").toPath())));
		assertEquals("decoy", new String(Files.readAllBytes(
				decoyDb.toPath()), "UTF-8"));
		assertEquals("hidden", new String(Files.readAllBytes(
				hiddenDb.toPath()), "UTF-8"));
		assertEquals("decoy", device.newProfileManager()
				.readLastActiveProfileId());
	}
}
