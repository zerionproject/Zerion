package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileSessionTest extends BrambleMockTestCase {

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;

	public ProfileSessionTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void aRunningSessionKeepsItsProfile() throws Exception {
		device.addProfile("u1", "password one");
		device.addProfile("u2", "password two");
		ProfileManager profiles = device.newProfileManager();
		List<String> told = new ArrayList<>();
		profiles.setSessionListener(told::add);
		assertNull(profiles.getSessionProfileId());

		profiles.startSession("u1");
		profiles.startSession("u1");

		assertEquals("u1", profiles.getSessionProfileId());
		assertEquals("u1", profiles.getActiveProfileId());
		assertEquals("told once", 1, told.size());
		try {
			profiles.setActiveProfileId("u2");
			fail();
		} catch (IllegalStateException expected) {
		}
		try {
			profiles.startSession("u2");
			fail();
		} catch (IllegalStateException expected) {
		}
		assertEquals("u1", profiles.getActiveProfileId());
	}

	@Test
	public void signingInStartsTheSessionOfTheProfileThatOpened()
			throws Exception {
		device.addProfile("u1", "password one");
		device.addProfile("u2", "password two");
		ProfileManager profiles = device.newProfileManager();
		List<String> told = new ArrayList<>();
		profiles.setSessionListener(told::add);
		AndroidAccountManager accountManager =
				device.newAccountManager(profiles);

		accountManager.signIn("password two".toCharArray());

		assertEquals("u2", profiles.getSessionProfileId());
		assertEquals(1, told.size());
		assertEquals("u2", told.get(0));
		assertTrue(accountManager.hasDatabaseKey());
	}

	@Test
	public void torStateLeavesTheDefaultProfileForTheDevice()
			throws Exception {
		device.addProfile("default", "password one");
		File oldTor = new File(device.profileDir("default"), "tor");
		assertTrue(oldTor.mkdirs());
		Files.write(new File(oldTor, "state").toPath(), new byte[] {7});

		ProfileManager profiles = device.newProfileManager();

		File torDir = profiles.getDeviceTorDir();
		assertEquals(new File(device.filesDir, "tor"), torDir);
		assertTrue(new File(torDir, "state").exists());
		assertFalse(oldTor.exists());
	}

	@Test
	public void torStateInADeletedDefaultProfileLeavesNoGhost()
			throws Exception {
		device.addProfile("u1", "password one");
		File oldTor = new File(device.profileDir("default"), "tor");
		assertTrue(oldTor.mkdirs());
		Files.write(new File(oldTor, "state").toPath(), new byte[] {7});

		ProfileManager profiles = device.newProfileManager();

		assertFalse(device.profileDir("default").exists());
		assertTrue(new File(profiles.getDeviceTorDir(), "state").exists());
		assertEquals(java.util.Collections.singletonList("u1"),
				profiles.listProfileIds());
	}
}
