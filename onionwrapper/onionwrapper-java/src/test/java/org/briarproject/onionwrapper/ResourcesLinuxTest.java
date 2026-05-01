package org.briarproject.onionwrapper;

import org.junit.Before;
import org.junit.Test;

import static org.briarproject.onionwrapper.util.OsUtils.isLinux;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class ResourcesLinuxTest {

	@Before
	public void setUp() {
		assumeTrue(isLinux());
	}

	@Test
	public void testCanLoadTor() {
		testCanLoadResource("x86_64/tor");
	}

	@Test
	public void testCanLoadLyrebird() {
		testCanLoadResource("x86_64/lyrebird");
	}

	private void testCanLoadResource(String name) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		assertNotNull(classLoader.getResourceAsStream(name));
	}
}
