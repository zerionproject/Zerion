package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileDeletionDisclosureTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileDeletionDisclosureTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private static String afterDeletion(ProfileTestDevice device) {
		AndroidAccountManager next = device.newAccountManager();
		List<String> left = device.newProfileManager().listProfileIds();
		return "account=" + next.accountExists() + " profiles=" + left.size();
	}

	@Test
	public void deletingTheOnlyProfileLooksLikeDeletingOneOfTwo()
			throws Exception {
		device.addProfile("default", DECOY);
		AndroidAccountManager alone = device.newAccountManager();
		alone.signIn(DECOY.toCharArray());
		assertTrue(alone.deleteActiveProfile("default"));
		String onlyProfile = afterDeletion(device);
		deleteTestDirectory(testDir);

		ProfileTestDevice two = new ProfileTestDevice(context,
				getTestDirectory());
		try {
			two.addProfile("default", DECOY);
			two.addProfile("b7c1", HIDDEN);
			AndroidAccountManager decoy = two.newAccountManager();
			decoy.signIn(DECOY.toCharArray());
			assertTrue(decoy.deleteActiveProfile("default"));
			String oneOfTwo = afterDeletion(two);

			assertEquals("account=true profiles=1", oneOfTwo);
			assertEquals(oneOfTwo, onlyProfile);
		} finally {
			deleteTestDirectory(two.testDir);
		}
	}

	@Test
	public void theDeletedProfileIsGoneAndItsPasswordOpensNothing()
			throws Exception {
		device.addProfile("default", DECOY);
		AndroidAccountManager accountManager = device.newAccountManager();
		accountManager.signIn(DECOY.toCharArray());
		assertTrue(accountManager.deleteActiveProfile("default"));

		assertFalse(device.keyFile("default").exists());
		device.newProfileManager();
		assertFalse(device.profileDir("default").exists());
		try {
			device.newAccountManager().signIn(DECOY.toCharArray());
			fail();
		} catch (DecryptionException expected) {
		}
	}

	@Test
	public void deletingAProfileCostsTheSameWhetherOrNotAnotherRemains()
			throws Exception {
		device.addProfile("default", DECOY);
		AndroidAccountManager alone = device.newAccountManager();
		alone.signIn(DECOY.toCharArray());
		int before = device.derivations.get();
		assertTrue(alone.deleteActiveProfile("default"));
		int onlyProfile = device.derivations.get() - before;

		ProfileTestDevice two = new ProfileTestDevice(context,
				getTestDirectory());
		try {
			two.addProfile("default", DECOY);
			two.addProfile("b7c1", HIDDEN);
			AndroidAccountManager decoy = two.newAccountManager();
			decoy.signIn(DECOY.toCharArray());
			before = two.derivations.get();
			assertTrue(decoy.deleteActiveProfile("default"));
			assertEquals(onlyProfile, two.derivations.get() - before);
		} finally {
			deleteTestDirectory(two.testDir);
		}
	}

	@Test
	public void noPlaceholderIsAddedWhileAnotherProfileRemains()
			throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		AndroidAccountManager accountManager = device.newAccountManager();
		accountManager.signIn(DECOY.toCharArray());
		assertTrue(accountManager.deleteActiveProfile("default"));

		assertEquals(java.util.Collections.singletonList("b7c1"),
				device.newProfileManager().listProfileIds());
		assertEquals(java.util.Collections.singletonList("b7c1"),
				device.newProfileManager().listProfileIds());
		device.newAccountManager().signIn(HIDDEN.toCharArray());
	}
}
