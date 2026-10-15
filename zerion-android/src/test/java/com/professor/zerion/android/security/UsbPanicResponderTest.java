package com.professor.zerion.android.security;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UsbPanicResponderTest {

	private static final class Steps implements UsbPanicResponder.Steps {

		final List<String> calls = new ArrayList<>();
		boolean signedIn = true;
		boolean stops = true;
		boolean gone = true;
		boolean wipeThrows = false;

		@Override
		public boolean signedIn() {
			return signedIn;
		}

		@Override
		public void abortCopies() {
			calls.add("abort copies");
		}

		@Override
		public void clearMemory() {
			calls.add("clear memory");
		}

		@Override
		public void shredKey() {
			calls.add("shred key");
		}

		@Override
		public boolean stopServices(long timeoutMs) {
			calls.add("stop services");
			return stops;
		}

		@Override
		public void sweepFiles() {
			calls.add("sweep files");
		}

		@Override
		public void wipeAccount() {
			calls.add("wipe account");
			if (wipeThrows) throw new IllegalStateException("disk error");
		}

		@Override
		public boolean accountGone() {
			calls.add("check account gone");
			return gone;
		}

		@Override
		public void exit() {
			calls.add("exit");
		}
	}

	@Test
	public void aSignOutStopsSweepsAndExitsOnlyOnceStopped() {
		Steps steps = new Steps();
		assertTrue(new UsbPanicResponder(false, steps).run());
		assertEquals("[abort copies, clear memory, stop services, sweep files,"
						+ " exit]", steps.calls.toString());
	}

	@Test
	public void aSignOutWhileSignedOutIsAlreadyDone() {
		Steps steps = new Steps();
		steps.signedIn = false;
		assertTrue(new UsbPanicResponder(false, steps).run());
		assertEquals("[]", steps.calls.toString());
	}

	@Test
	public void aSignOutThatCannotStopIsAFailureAndTheFallbackExits() {
		Steps steps = new Steps();
		steps.stops = false;
		UsbPanicResponder responder = new UsbPanicResponder(false, steps);
		assertFalse(responder.run());
		assertFalse("no exit after a failed attempt",
				steps.calls.contains("exit"));
		responder.giveUp();
		assertEquals("exit", steps.calls.get(steps.calls.size() - 1));
	}

	@Test
	public void aWipeMakesTheKeyUnusableFirstAndExitsOnlyWhenTheAccountIsGone() {
		Steps steps = new Steps();
		assertTrue(new UsbPanicResponder(true, steps).run());
		assertEquals("[abort copies, clear memory, shred key, stop services,"
						+ " sweep files, wipe account, check account gone,"
						+ " exit]", steps.calls.toString());
	}

	@Test
	public void aWipeWhileSignedOutStillWipes() {
		Steps steps = new Steps();
		steps.signedIn = false;
		assertTrue(new UsbPanicResponder(true, steps).run());
		assertTrue(steps.calls.contains("shred key"));
		assertTrue(steps.calls.contains("wipe account"));
		assertTrue(steps.calls.contains("exit"));
	}

	@Test
	public void aWipeThatLeavesTheAccountIsAFailure() {
		Steps steps = new Steps();
		steps.gone = false;
		UsbPanicResponder responder = new UsbPanicResponder(true, steps);
		assertFalse(responder.run());
		assertFalse(steps.calls.contains("exit"));
		responder.giveUp();
		assertTrue(steps.calls.contains("exit"));
	}

	@Test
	public void aWipeThatThrowsHasAlreadyMadeTheKeyUnusable() {
		Steps steps = new Steps();
		steps.wipeThrows = true;
		try {
			new UsbPanicResponder(true, steps).run();
			fail("the failure was hidden");
		} catch (IllegalStateException expected) {
		}
		assertEquals("shred key", steps.calls.get(2));
		assertFalse(steps.calls.contains("exit"));
	}

	@Test
	public void theFallbackLeavesASignedOutAppAlone() {
		Steps steps = new Steps();
		steps.signedIn = false;
		new UsbPanicResponder(false, steps).giveUp();
		assertEquals("[]", steps.calls.toString());
	}
}
