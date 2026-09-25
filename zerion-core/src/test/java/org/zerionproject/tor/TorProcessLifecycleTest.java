package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.tor.AbstractTorWrapper.EXIT_TIMEOUT_MS;
import static org.zerionproject.tor.AbstractTorWrapper.KILL_TIMEOUT_MS;
import static org.zerionproject.tor.TorWrapper.TorState.STOPPED;

/**
 * SC-TOR-01: a Tor process that will not exit is killed within a bound, a
 * process that never opens its control listener is given up on, and a
 * start that fails or is interrupted at any point leaves no process
 * behind and no state the wrapper cannot recover from.
 */
public class TorProcessLifecycleTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void anExitedProcessIsSeenAtOnce() throws Exception {
		Process p = ChildProcess.spawn(ChildProcess.EXIT);
		p.waitFor();
		long t = System.currentTimeMillis();
		assertTrue(AbstractTorWrapper.awaitExit(p, 5_000));
		assertTrue(System.currentTimeMillis() - t < 1_000);
	}

	@Test
	public void aLiveProcessIsReportedAliveAfterTheTimeout()
			throws Exception {
		Process p = ChildProcess.spawn(ChildProcess.SLEEP);
		try {
			long t = System.currentTimeMillis();
			assertFalse(AbstractTorWrapper.awaitExit(p, 300));
			long took = System.currentTimeMillis() - t;
			assertTrue(String.valueOf(took), took >= 300 && took < 3_000);
		} finally {
			p.destroy();
		}
	}

	@Test
	public void aProcessThatIgnoresShutdownIsKilledWithinTheBound()
			throws Exception {
		Process p = ChildProcess.spawn(ChildProcess.SLEEP);
		long t = System.currentTimeMillis();
		boolean exitedOnItsOwn = AbstractTorWrapper.terminate(p);
		long took = System.currentTimeMillis() - t;
		assertFalse(exitedOnItsOwn);
		assertTrue(AbstractTorWrapper.awaitExit(p, 0));
		assertTrue(String.valueOf(took),
				took < EXIT_TIMEOUT_MS + 2 * KILL_TIMEOUT_MS + 2_000);
	}

	@Test
	public void aProcessThatExitsIsNotKilled() throws Exception {
		Process p = ChildProcess.spawn(ChildProcess.EXIT);
		assertTrue(AbstractTorWrapper.terminate(p));
	}

	@Test
	public void waitingForTheListenerSucceedsOnTheListenerLine()
			throws Exception {
		TestTorWrapper w = wrapper();
		Process p = ChildProcess.spawn(ChildProcess.LISTEN);
		try {
			w.waitForTorToStart(p, 10_000);
		} finally {
			p.destroy();
		}
	}

	@Test
	public void waitingForTheListenerFailsWhenTheProcessExitsSilently()
			throws Exception {
		TestTorWrapper w = wrapper();
		Process p = ChildProcess.spawn(ChildProcess.EXIT);
		try {
			w.waitForTorToStart(p, 10_000);
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("exited"));
		}
	}

	@Test
	public void waitingForTheListenerGivesUpOnASilentProcess()
			throws Exception {
		TestTorWrapper w = wrapper();
		w.startTimeoutMs = 500;
		Process p = ChildProcess.spawn(ChildProcess.SLEEP);
		try {
			long t = System.currentTimeMillis();
			try {
				w.waitForTorToStart(p, 500);
				fail();
			} catch (IOException expected) {
				assertTrue(expected.getMessage().contains("in time"));
			}
			assertTrue(System.currentTimeMillis() - t < 5_000);
		} finally {
			p.destroy();
		}
	}

	@Test
	public void aStartThatTimesOutKillsTheProcessAndCanBeRetried()
			throws Exception {
		TestTorWrapper w = wrapper();
		w.torScript = ChildProcess.script(tmp.getRoot(), "silent",
				ChildProcess.SLEEP);
		w.startTimeoutMs = 500;
		try {
			w.start();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("in time"));
		}
		Process launched = w.launched;
		assertNotNull(launched);
		assertTrue(AbstractTorWrapper.awaitExit(launched, 0));
		assertEquals(STOPPED, w.getTorState());
		assertFalse(w.isTorRunning());
		try {
			w.start();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("in time"));
		}
		assertEquals(STOPPED, w.getTorState());
	}

	@Test
	public void aStartWhoseProcessExitsAtOnceIsAFailureNotAHang()
			throws Exception {
		TestTorWrapper w = wrapper();
		w.torScript = ChildProcess.script(tmp.getRoot(), "dies",
				ChildProcess.EXIT);
		try {
			w.start();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("exited"));
		}
		assertEquals(STOPPED, w.getTorState());
	}

	@Test
	public void anInterruptedStartLeavesNoProcessBehind() throws Exception {
		TestTorWrapper w = wrapper();
		w.torScript = ChildProcess.script(tmp.getRoot(), "silent2",
				ChildProcess.SLEEP);
		Thread.currentThread().interrupt();
		try {
			w.start();
			fail();
		} catch (InterruptedException expected) {
		}
		Process launched = w.launched;
		assertNotNull(launched);
		assertTrue(AbstractTorWrapper.awaitExit(launched, 0));
		assertEquals(STOPPED, w.getTorState());
		assertFalse(w.isTorRunning());
	}

	@Test
	public void stopBeforeStartIsANoOp() throws Exception {
		TestTorWrapper w = wrapper();
		w.stop();
		assertEquals(TorWrapper.TorState.NOT_STARTED, w.getTorState());
	}

	private TestTorWrapper wrapper() throws IOException {
		File dir = tmp.newFolder();
		return new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
		});
	}
}
