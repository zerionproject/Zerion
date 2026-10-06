package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_FILES_DAMAGED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileSignInPaddingTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";
	private static final String WRONG = "not a password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileSignInPaddingTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private static void damage(ProfileTestDevice device, String id) {
		File keyDir = device.keyFile(id).getParentFile();
		assertTrue(new File(keyDir, "db.key").delete());
		assertTrue(new File(keyDir, "db.key.state").delete());
	}

	private static int wrongPasswordDerivations(ProfileTestDevice device,
			AndroidAccountManager accountManager) {
		int before = device.derivations.get();
		try {
			accountManager.signIn(WRONG.toCharArray());
			fail();
		} catch (DecryptionException expected) {
		}
		return device.derivations.get() - before;
	}

	@Test
	public void aDamagedHiddenProfileDoesNotShortenTheWork()
			throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		damage(device, "b7c1");
		ProfileManager profiles = device.newProfileManager();
		profiles.writeLastActiveProfileId("default");

		int derivations = wrongPasswordDerivations(device,
				device.newAccountManager(profiles));

		assertEquals(AndroidAccountManager.MIN_TIMED_ATTEMPTS, derivations);
	}

	@Test
	public void aDamagedLastUsedProfileIsReportedTheSameWithOrWithoutAnother()
			throws Exception {
		device.addProfile("default", DECOY);
		damage(device, "default");
		AndroidAccountManager alone = device.newAccountManager();
		int aloneDerivations = wrongPasswordDerivations(device, alone);
		int aloneFailures = alone.failedSignInAttempts();

		ProfileTestDevice two = new ProfileTestDevice(context,
				getTestDirectory());
		try {
			two.addProfile("default", DECOY);
			two.addProfile("b7c1", HIDDEN);
			damage(two, "default");
			ProfileManager profiles = two.newProfileManager();
			profiles.writeLastActiveProfileId("default");
			AndroidAccountManager withHidden = two.newAccountManager(profiles);
			try {
				withHidden.signIn(WRONG.toCharArray());
				fail();
			} catch (DecryptionException e) {
				assertEquals(KEY_FILES_DAMAGED, e.getDecryptionResult());
			}
			assertEquals("the same throttle entry", aloneFailures,
					withHidden.failedSignInAttempts());
			assertEquals("the same work", aloneDerivations,
					wrongPasswordDerivations(two, withHidden));
		} finally {
			deleteTestDirectory(two.testDir);
		}
	}
}
