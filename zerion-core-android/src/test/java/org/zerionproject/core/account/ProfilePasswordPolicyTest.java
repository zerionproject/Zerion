package org.zerionproject.core.account;

import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfilePasswordPolicyTest extends BrambleMockTestCase {

	private static final String FIRST = "first profile 2@Hj";
	private static final String SECOND = "second profile 4!Kd";

	private final File testDir = getTestDirectory();
	private final ProfileTestDevice device;
	private final AtomicLong clock = new AtomicLong(1_000_000L);

	public ProfilePasswordPolicyTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		device = new ProfileTestDevice(context, testDir);
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private AndroidAccountManager process() {
		return device.newAccountManager(device.newProfileManager(),
				clock::get);
	}

	private String signIn(AndroidAccountManager m, String password) {
		clock.addAndGet(86_400_001L);
		try {
			m.signIn(password.toCharArray());
			return "opens " + m.getActiveProfileId();
		} catch (DecryptionException e) {
			return e.getDecryptionResult().name();
		}
	}

	@Test
	public void aProfileCreatedWithOneTypingIsSignedIntoWithAnother()
			throws Exception {
		device.addProfile("default", FIRST);
		AndroidAccountManager session = process();
		session.signIn(FIRST.toCharArray());
		String id = session.scheduleProfileCreation("Second",
				"second​ profile 4!Kd".toCharArray());
		assertEquals("opens " + id, signIn(process(), SECOND));
	}

	@Test
	public void damagedKeyFilesOfAnotherProfileNeverLeadToAnErase()
			throws Exception {
		device.addProfile("aaaa", SECOND);
		device.addProfile("bbbb", FIRST);
		Files.write(device.keyFile("aaaa").toPath(),
				"not a key".getBytes(StandardCharsets.UTF_8));
		new File(device.keyFile("aaaa").getParentFile(), "db.key.state")
				.delete();
		new File(device.keyFile("aaaa").getParentFile(), "db.key.bak")
				.delete();
		AndroidAccountManager hint = process();
		hint.signIn(FIRST.toCharArray());

		AndroidAccountManager m = process();
		m.setErasePolicy(failures -> failures >= 6);
		String last = null;
		for (int i = 0; i < 8; i++) last = signIn(m, SECOND);
		assertEquals("refused INVALID_CIPHERTEXT, counted 8, erase not"
						+ " recorded, healthy profile still opens",
				"refused " + last + ", counted " + m.failedSignInAttempts()
						+ ", erase " + (m.isEraseRequested() ? "recorded"
						: "not recorded") + ", healthy profile still "
						+ signIn(process(), FIRST).split(" ")[0]);
	}

	@Test
	public void wrongPasswordsAgainstHealthyProfilesStillErase()
			throws Exception {
		device.addProfile("aaaa", SECOND);
		device.addProfile("bbbb", FIRST);
		AndroidAccountManager m = process();
		m.setErasePolicy(failures -> failures >= 6);
		for (int i = 0; i < 6; i++) signIn(m, "a wrong password 1!Aa");
		assertEquals("erase recorded, keys gone",
				"erase " + (m.isEraseRequested() ? "recorded"
						: "not recorded") + ", keys "
						+ (device.keyFile("aaaa").exists()
						|| device.keyFile("bbbb").exists() ? "kept" : "gone"));
	}
}
