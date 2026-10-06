package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.zerionproject.core.account.AndroidAccountManager.ProfileCreationRefusal.LOCKED_OUT;
import static org.zerionproject.core.account.AndroidAccountManager.ProfileCreationRefusal.PASSWORD_UNAVAILABLE;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfilePasswordCheckThrottleTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;
	private ProfileManager profiles;
	private AndroidAccountManager accountManager;

	public ProfilePasswordCheckThrottleTest() {
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

	private static char[] fresh(int i) {
		return ("fresh password " + i).toCharArray();
	}

	@Test
	public void aSessionGetsThreeChecksAndThenNoneWithoutAnyDerivation() {
		for (int i = 0; i < 3; i++) {
			assertNotNull("check " + i,
					accountManager.scheduleProfileCreation("P" + i, fresh(i)));
		}
		int before = device.derivations.get();

		assertNull(accountManager.scheduleProfileCreation("P3", fresh(3)));

		assertEquals(LOCKED_OUT, accountManager.getLastProfileCreationRefusal());
		assertEquals("a refused check derives nothing", before,
				device.derivations.get());
		assertEquals(5, profiles.listProfileIds().size());
	}

	@Test
	public void aCheckThatFindsTheGuessedPasswordCountsToo() {
		for (int i = 0; i < 3; i++) {
			assertNull(accountManager.scheduleProfileCreation("P" + i,
					HIDDEN.toCharArray()));
			assertEquals(PASSWORD_UNAVAILABLE,
					accountManager.getLastProfileCreationRefusal());
		}

		assertNull(accountManager.scheduleProfileCreation("P3",
				HIDDEN.toCharArray()));

		assertEquals(LOCKED_OUT, accountManager.getLastProfileCreationRefusal());
	}

	@Test
	public void importingABackupIsAGuessLikeAnyOther() {
		byte[] db = new byte[] {1, 2, 3};
		byte[] key = new byte[32];
		for (int i = 0; i < 3; i++) {
			assertNotNull(accountManager.importProfile("I" + i, fresh(i), db,
					key));
		}

		assertNull(accountManager.importProfile("I3", fresh(3), db, key));

		assertEquals(LOCKED_OUT, accountManager.getLastProfileCreationRefusal());
	}

	@Test
	public void theSignedInPasswordDoesNotBuyMoreChecks() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertNotNull(accountManager.scheduleProfileCreation("P" + i,
					fresh(i)));
		}
		accountManager.verifyPassword(DECOY.toCharArray());
		assertEquals(0, accountManager.failedSignInAttempts());

		assertNull(accountManager.scheduleProfileCreation("P3", fresh(3)));

		assertEquals(LOCKED_OUT, accountManager.getLastProfileCreationRefusal());
	}

	@Test
	public void changingThePasswordIsRefusedOnceTheChecksAreUsedUp()
			throws Exception {
		for (int i = 0; i < 3; i++) {
			assertNotNull(accountManager.scheduleProfileCreation("P" + i,
					fresh(i)));
		}
		try {
			accountManager.changePassword(DECOY.toCharArray(), fresh(3));
			fail();
		} catch (DecryptionException expected) {
		}
		accountManager.verifyPassword(DECOY.toCharArray());
	}

	@Test
	public void theChecksAreCountedAcrossProcesses() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertNotNull(accountManager.scheduleProfileCreation("P" + i,
					fresh(i)));
		}

		AndroidAccountManager next = device.newAccountManager();
		next.signIn(DECOY.toCharArray());

		assertNull(next.scheduleProfileCreation("P3", fresh(3)));
		assertEquals(LOCKED_OUT, next.getLastProfileCreationRefusal());
	}
}
