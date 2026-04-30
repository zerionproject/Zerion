package org.briarproject.onionwrapper;

import org.junit.Before;
import org.junit.Test;

import static org.briarproject.onionwrapper.MacTorWrapper.LIB_EVENT_VERSION;
import static org.briarproject.onionwrapper.util.OsUtils.isMac;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class ResourcesMacTest {

	@Before
	public void setUp() {
		assumeTrue(isMac());
	}

	@Test
	public void testCanLoadTor() {
		testCanLoadResource("x86_64/tor");
	}

	@Test
	public void testCanLoadLibEvent() {
		testCanLoadResource("x86_64/libevent-" + LIB_EVENT_VERSION + ".dylib");
	}

	@Test
	public void testCanLoadLyrebird() {
		testCanLoadResource("x86_64/lyrebird");
	}

	private void testCanLoadResource(String name) {
		ClassLoader classLoader =
				Thread.currentThread().getContextClassLoader();
		assertNotNull(classLoader.getResourceAsStream(name));
	}
}
