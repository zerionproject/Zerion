package org.briarproject.onionwrapper;

import org.junit.After;
import org.junit.Before;

import java.util.logging.Logger;

import javax.annotation.Nullable;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;

class BaseTest {

	private static final Logger LOG = getLogger(BaseTest.class.getName());

	@Nullable
	protected volatile Throwable exceptionInBackgroundThread = null;

	BaseTest() {
		// Ensure exceptions thrown on worker threads cause tests to fail
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
			LOG.log(WARNING, "Caught unhandled exception", throwable);
			exceptionInBackgroundThread = throwable;
		});
	}

	@Before
	public void beforeTestCase() {
		exceptionInBackgroundThread = null;
	}

	@After
	public void afterTestCase() {
		Throwable thrown = exceptionInBackgroundThread;
		if (thrown != null) {
			LOG.log(WARNING, "Background thread has thrown an exception unexpectedly", thrown);
			throw new AssertionError(thrown);
		}
	}
}
