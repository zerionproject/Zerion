package org.zerionproject.core.api.plugin;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OnionTargetsTest {

	@Test
	public void theTwoSpellingsAreBuiltAndRecognised() {
		String unix = OnionTargets.unixPath("/data/user/0/app/files/zo/0a1b");
		assertEquals("unix:/data/user/0/app/files/zo/0a1b", unix);
		assertTrue(OnionTargets.isUnix(unix));
		assertTrue(OnionTargets.isValid(unix));
		assertEquals(-1, OnionTargets.loopbackPort(unix));
		String tcp = OnionTargets.loopback(4321);
		assertEquals("127.0.0.1:4321", tcp);
		assertEquals(4321, OnionTargets.loopbackPort(tcp));
		assertFalse(OnionTargets.isUnix(tcp));
		assertTrue(OnionTargets.isValid(tcp));
		if (File.separatorChar == '/') {
			assertTrue(OnionTargets.isUnix(OnionTargets.unix(
					new File("/data/zs/abc"))));
		}
	}

	@Test
	public void aTargetThatCouldEndTheCommandIsRefused() {
		String[] bad = {"unix:/a b", "unix:/a\r\nGETINFO x", "unix:/a\nx",
				"unix:/a,80", "unix:relative", "unix:\"/a\"", "unix:/a;b",
				"unix:/../etc", "unix:", "10.0.0.1:80", "127.0.0.1:0",
				"127.0.0.1:65536", "127.0.0.1:80 Port=81", "localhost:80",
				"", "unix:/" + repeat('a', OnionTargets.MAX_UNIX_PATH_BYTES)};
		for (String b : bad) assertFalse(b, OnionTargets.isValid(b));
		for (String path : new String[] {"/a b", "relative", "/a\"",
				"/" + repeat('a', OnionTargets.MAX_UNIX_PATH_BYTES)}) {
			try {
				OnionTargets.unixPath(path);
				fail(path);
			} catch (IllegalArgumentException expected) {
			}
		}
		try {
			OnionTargets.loopback(0);
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append(c);
		return sb.toString();
	}
}
