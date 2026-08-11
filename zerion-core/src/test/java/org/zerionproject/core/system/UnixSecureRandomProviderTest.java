package org.zerionproject.core.system;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.security.Provider;

import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.util.OsUtils.isLinux;
import static org.zerionproject.core.util.OsUtils.isMac;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class UnixSecureRandomProviderTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();

	@Before
	public void setUp() {
		assumeTrue(isLinux() || isMac());
		assertTrue(testDir.mkdirs());
	}

	@Test
	public void testGetProviderWritesToRandomDeviceOnFirstCall()
			throws Exception {

		File urandom = new File(testDir, "urandom");
		if (urandom.exists()) assertTrue(urandom.delete());
		assertTrue(urandom.createNewFile());
		assertEquals(0, urandom.length());
		UnixSecureRandomProvider p = new UnixSecureRandomProvider(urandom);

		Provider provider = p.getProvider();
		assertNotNull(provider);
		assertEquals("UnixPRNG", provider.getName());

		long length = urandom.length();
		assertTrue(length >= 24);

		provider = p.getProvider();
		assertNotNull(provider);
		assertEquals("UnixPRNG", provider.getName());
		assertEquals(length, urandom.length());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}
}
