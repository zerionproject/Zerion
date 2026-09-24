package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.tor.TorWrapper.TorState.STOPPED;

/**
 * SC-TOR-02: the wrapper hands the files it is about to execute to the
 * verifier before it launches anything, and a verifier that rejects them
 * stops the start with nothing launched. There is no way to start Tor
 * around the check.
 */
public class TorBinaryVerificationTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aRejectedExecutableIsNeverLaunched() throws Exception {
		File dir = tmp.newFolder();
		TestTorWrapper w = new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
			throw new IOException("libtor.so does not match its pin");
		});
		w.torScript = ChildProcess.script(dir, "tor", ChildProcess.LISTEN);
		try {
			w.start();
			fail();
		} catch (IOException expected) {
			assertEquals("libtor.so does not match its pin",
					expected.getMessage());
		}
		assertNull(w.launched);
		assertEquals(STOPPED, w.getTorState());
		assertFalse(w.isTorRunning());
	}

	@Test
	public void theVerifierSeesTheFilesThatWouldRun() throws Exception {
		File dir = tmp.newFolder();
		List<File> seen = new ArrayList<>();
		TestTorWrapper w = new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
			seen.add(t);
			seen.add(l);
			throw new IOException("stop here");
		});
		try {
			w.start();
			fail();
		} catch (IOException expected) {
			assertEquals("stop here", expected.getMessage());
		}
		assertEquals(2, seen.size());
		assertEquals(new File(dir, "tor"), seen.get(0));
		assertEquals(new File(dir, "lyrebird"), seen.get(1));
		assertTrue(seen.get(0).isFile());
		assertTrue(seen.get(1).isFile());
	}

	@Test
	public void aRejectedStartCanBeRetriedAndStaysRejected()
			throws Exception {
		File dir = tmp.newFolder();
		int[] calls = {0};
		TestTorWrapper w = new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
			calls[0]++;
			throw new IOException("no");
		});
		for (int i = 0; i < 3; i++) {
			try {
				w.start();
				fail();
			} catch (IOException expected) {
			}
			assertEquals(STOPPED, w.getTorState());
		}
		assertEquals(3, calls[0]);
		assertNull(w.launched);
	}

	@Test
	public void aRuntimeFailureInTheVerifierAlsoStopsTheStart()
			throws Exception {
		File dir = tmp.newFolder();
		TestTorWrapper w = new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
			throw new IllegalStateException("pins unreadable");
		});
		try {
			w.start();
			fail();
		} catch (IllegalStateException expected) {
		}
		assertNull(w.launched);
		assertEquals(STOPPED, w.getTorState());
	}

	@Test
	public void theExecutableIsCheckedBeforeEveryStart() throws Exception {
		File dir = tmp.newFolder();
		int[] calls = {0};
		TestTorWrapper w = new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
			calls[0]++;
		});
		w.torScript = ChildProcess.script(dir, "dies", ChildProcess.EXIT);
		for (int i = 0; i < 2; i++) {
			try {
				w.start();
				fail();
			} catch (IOException expected) {
				assertTrue(expected.getMessage().contains("exited"));
			}
		}
		assertEquals(2, calls[0]);
	}
}
