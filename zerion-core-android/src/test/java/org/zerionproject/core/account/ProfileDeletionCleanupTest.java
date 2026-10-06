package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileDeletionCleanupTest extends BrambleMockTestCase {

	private static final String DECOY = "decoy password";
	private static final String HIDDEN = "hidden password";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileDeletionCleanupTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void aLateWriteAfterDeletionDoesNotSurviveTheNextStart()
			throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		AndroidAccountManager accountManager = device.newAccountManager();
		accountManager.signIn(DECOY.toCharArray());
		assertTrue(accountManager.deleteActiveProfile("default"));

		File late = new File(device.profileDir("default"),
				"settings/profile.prefs");
		assertTrue(late.getParentFile().isDirectory()
				|| late.getParentFile().mkdirs());
		Files.write(late.toPath(), new byte[] {1, 2, 3});

		ProfileManager next = device.newProfileManager();
		assertFalse("the deleted profile's directory is gone",
				device.profileDir("default").exists());
		assertEquals(Collections.singletonList("b7c1"),
				next.listProfileIds());
	}

	@Test
	public void theKeyIsShreddedAtOnceAndThePasswordOpensNothing()
			throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		ProfileManager profiles = device.newProfileManager();
		AndroidAccountManager accountManager =
				device.newAccountManager(profiles);
		accountManager.signIn(DECOY.toCharArray());
		assertTrue(accountManager.deleteActiveProfile("default"));

		assertFalse(profiles.hasKeyFiles("default"));
		assertFalse(device.keyFile("default").exists());
		try {
			device.newAccountManager().signIn(DECOY.toCharArray());
			fail();
		} catch (DecryptionException expected) {
		}
	}

	@Test
	public void theLastUsedHintStopsNamingTheDeletedProfile()
			throws Exception {
		device.addProfile("default", DECOY);
		device.addProfile("b7c1", HIDDEN);
		ProfileManager profiles = device.newProfileManager();
		AndroidAccountManager accountManager =
				device.newAccountManager(profiles);
		accountManager.signIn(DECOY.toCharArray());
		assertEquals("default", profiles.readLastActiveProfileId());

		assertTrue(accountManager.deleteActiveProfile("default"));

		assertNotEquals("default", profiles.readLastActiveProfileId());
		assertNotEquals("default",
				device.newProfileManager().readLastActiveProfileId());
	}

	@Test
	public void aProfileDirectoryWithoutAKeyIsWipedAtStart()
			throws Exception {
		device.addProfile("b7c1", HIDDEN);
		File ghostDb = new File(device.profileDir("ghost"), "db/db.sqlite");
		assertTrue(ghostDb.getParentFile().mkdirs());
		Files.write(ghostDb.toPath(), new byte[] {4, 5, 6});
		File ghostBlob = new File(device.profileDir("ghost"),
				"channel-blobs/x/y.bin");
		assertTrue(ghostBlob.getParentFile().mkdirs());
		Files.write(ghostBlob.toPath(), new byte[] {7});

		ProfileManager profiles = device.newProfileManager();

		assertFalse(device.profileDir("ghost").exists());
		assertEquals(Collections.singletonList("b7c1"),
				profiles.listProfileIds());
	}
}
