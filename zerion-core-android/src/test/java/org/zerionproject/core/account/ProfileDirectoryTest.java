package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileDirectoryTest extends BrambleMockTestCase {

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileDirectoryTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void resolvingPathsOfAMissingProfileCreatesNothing()
			throws Exception {
		device.addProfile("u1", "password one");
		ProfileManager profiles = device.newProfileManager();

		profiles.getActiveKeyDir();
		profiles.getActiveDbDir();
		profiles.getKeyDir("default");
		profiles.getDbDir("default");
		profiles.getDbKeyFile("default");
		profiles.getDbKeyBackupFile("default");
		profiles.getDisplayNameFile("default");
		profiles.readDisplayName("default");
		profiles.readEncryptedMetaFile("default", "pending_identity_name");
		profiles.deleteMetaFile("default", "pending_identity_name");
		assertFalse(profiles.writeDisplayName("default", "Nobody"));

		assertFalse(device.profileDir("default").exists());
		assertEquals(Collections.singletonList("u1"),
				profiles.listProfileIds());
	}

	@Test
	public void theSignInThrottleCreatesNoProfileDirectory()
			throws Exception {
		device.addProfile("u1", "password one");
		AndroidAccountManager accountManager = device.newAccountManager();

		accountManager.signInLockoutRemainingMs();
		accountManager.failedSignInAttempts();
		assertTrue(accountManager.accountExists());

		assertFalse("no ghost of the default profile",
				device.profileDir("default").exists());
		assertEquals(Collections.singletonList("u1"),
				device.newProfileManager().listProfileIds());
	}

	@Test
	public void directoriesLeftByEarlierVersionsAreRemoved()
			throws Exception {
		device.addProfile("u1", "password one");
		assertTrue(new File(device.profileDir("default"), "key").mkdirs());
		assertTrue(new File(device.profileDir("u1"), "tor").mkdirs());
		File marker = new File(device.filesDir, "profiles/.multi");
		assertTrue(marker.createNewFile());

		ProfileManager profiles = device.newProfileManager();

		assertFalse("the empty default profile is gone",
				device.profileDir("default").exists());
		assertFalse("so is the marker that a second profile once existed",
				marker.exists());
		assertFalse(new File(device.profileDir("u1"), "tor").exists());
		assertTrue(device.keyFile("u1").exists());
		assertEquals(Collections.singletonList("u1"),
				profiles.listProfileIds());
	}

	@Test
	public void aProfileWithAKeyIsKeptAndOneWithoutIsWiped()
			throws Exception {
		device.addProfile("u1", "password one");
		Files.write(new File(device.profileDir("u1"), "db/db.sqlite")
				.toPath(), new byte[] {1});
		assertTrue(new File(device.keyFile("u1").getParentFile(),
				"db.key.state").delete());
		assertTrue(new File(device.profileDir("keyless"), "db").mkdirs());
		Files.write(new File(device.profileDir("keyless"), "db/db.sqlite")
				.toPath(), new byte[] {1});

		ProfileManager profiles = device.newProfileManager();

		assertEquals(Collections.singletonList("u1"),
				profiles.listProfileIds());
		assertFalse(device.profileDir("keyless").exists());
	}

	@Test
	public void dataNoKeyOpensIsKeptWhenNoProfileHasAKey()
			throws Exception {
		File db = new File(device.profileDir("default"), "db/db.sqlite");
		assertTrue(db.getParentFile().mkdirs());
		Files.write(db.toPath(), new byte[] {1});
		File state = new File(device.profileDir("default"),
				"key/db.key.state");
		assertTrue(state.getParentFile().mkdirs());
		Files.write(state.toPath(), new byte[] {1});

		ProfileManager profiles = device.newProfileManager();

		assertTrue("left for the user to keep or erase", db.exists());
		assertTrue(state.exists());
		assertEquals(Collections.singletonList("default"),
				profiles.listProfileIds());
	}

	@Test
	public void anEmptyProfileWithoutKeysIsRemovedEvenWithoutAnAccount()
			throws Exception {
		assertTrue(new File(device.profileDir("default"), "key").mkdirs());
		assertTrue(new File(device.profileDir("default"), "db").mkdirs());

		ProfileManager profiles = device.newProfileManager();

		assertFalse(device.profileDir("default").exists());
		assertEquals(Collections.emptyList(), profiles.listProfileIds());
	}
}
