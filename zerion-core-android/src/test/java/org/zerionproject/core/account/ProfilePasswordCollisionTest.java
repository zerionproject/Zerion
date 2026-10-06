package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfilePasswordCollisionTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";
	private static final String FRESH = "fresh password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;
	private ProfileManager profiles;
	private AndroidAccountManager accountManager;

	public ProfilePasswordCollisionTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@Before
	public void setUp() throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		profiles = device.newProfileManager();
		accountManager = device.newAccountManager(profiles);
		accountManager.signIn(DECOY.toCharArray());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void aNewProfileCannotTakeAPasswordThatOpensAnotherProfile()
			throws Exception {
		byte[] hiddenKey = Files.readAllBytes(device.keyFile("b7c1").toPath());

		String id = accountManager.scheduleProfileCreation("Third",
				HIDDEN.toCharArray());

		assertNull(id);
		assertEquals(AndroidAccountManager.ProfileCreationRefusal
						.PASSWORD_UNAVAILABLE,
				accountManager.getLastProfileCreationRefusal());
		assertEquals(2, profiles.listProfileIds().size());
		assertEquals("default", profiles.getActiveProfileId());
		assertArrayEquals(hiddenKey,
				Files.readAllBytes(device.keyFile("b7c1").toPath()));
	}

	@Test
	public void norThePasswordOfTheSignedInProfile() {
		assertNull(accountManager.scheduleProfileCreation("Third",
				DECOY.toCharArray()));
		assertEquals(AndroidAccountManager.ProfileCreationRefusal
						.PASSWORD_UNAVAILABLE,
				accountManager.getLastProfileCreationRefusal());
		assertEquals(2, profiles.listProfileIds().size());
	}

	@Test
	public void aFreshPasswordCreatesTheProfileWithoutSwitchingProfiles()
			throws Exception {
		String id = accountManager.scheduleProfileCreation("Third",
				FRESH.toCharArray());

		assertNotNull(id);
		assertNull(accountManager.getLastProfileCreationRefusal());
		assertEquals("default", profiles.getActiveProfileId());
		assertEquals(3, profiles.listProfileIds().size());
		ProfileManager next = device.newProfileManager();
		device.newAccountManager(next).signIn(FRESH.toCharArray());
		assertEquals(id, next.getSessionProfileId());
	}

	@Test
	public void theCheckDoesTheSameWorkWhetherOrNotThePasswordIsTaken() {
		int before = device.derivations.get();
		accountManager.scheduleProfileCreation("Third",
				HIDDEN.toCharArray());
		int refused = device.derivations.get() - before;

		before = device.derivations.get();
		accountManager.scheduleProfileCreation("Third",
				FRESH.toCharArray());
		int createdWithoutTheNewKey = device.derivations.get() - before - 1;

		assertEquals(2, refused);
		assertEquals(refused, createdWithoutTheNewKey);
	}

	@Test
	public void theWorkDoesNotDependOnWhetherAHiddenProfileExists()
			throws Exception {
		int before = device.derivations.get();
		accountManager.scheduleProfileCreation("Third", FRESH.toCharArray());
		int createWithHidden = device.derivations.get() - before;
		before = device.derivations.get();
		accountManager.changePassword(DECOY.toCharArray(),
				"another fresh one".toCharArray());
		int changeWithHidden = device.derivations.get() - before;

		ProfileTestDevice alone = new ProfileTestDevice(context,
				getTestDirectory());
		try {
			alone.addProfile("default", DECOY);
			AndroidAccountManager single = alone.newAccountManager();
			single.signIn(DECOY.toCharArray());
			before = alone.derivations.get();
			single.scheduleProfileCreation("Third", FRESH.toCharArray());
			assertEquals(createWithHidden, alone.derivations.get() - before);
			before = alone.derivations.get();
			single.changePassword(DECOY.toCharArray(),
					"another fresh one".toCharArray());
			assertEquals(changeWithHidden, alone.derivations.get() - before);
		} finally {
			deleteTestDirectory(alone.testDir);
		}
	}

	@Test
	public void changingThePasswordToAnotherProfilesIsRefused()
			throws Exception {
		byte[] decoyKey = Files.readAllBytes(device.keyFile("default")
				.toPath());
		try {
			accountManager.changePassword(DECOY.toCharArray(),
					HIDDEN.toCharArray());
			fail();
		} catch (PasswordUnavailableException expected) {
		}
		assertArrayEquals(decoyKey,
				Files.readAllBytes(device.keyFile("default").toPath()));
		accountManager.verifyPassword(DECOY.toCharArray());
	}

	@Test
	public void aWrongCurrentPasswordIsRefusedBeforeTheNewOneIsChecked() {
		try {
			accountManager.changePassword("not it".toCharArray(),
					HIDDEN.toCharArray());
			fail();
		} catch (PasswordUnavailableException e) {
			fail("the new password was checked for a stranger");
		} catch (DecryptionException expected) {
		}
	}

	@Test
	public void changingToAFreshPasswordWorks() throws Exception {
		accountManager.changePassword(DECOY.toCharArray(),
				FRESH.toCharArray());
		accountManager.verifyPassword(FRESH.toCharArray());
	}
}
