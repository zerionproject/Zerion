package org.briarproject.bramble.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public class LogUtils {

	/**
	 * Logs an exception at the specified level.
	 *
	 * @param logger The logger to use
	 * @param level The logging level
	 * @param e The exception to log
	 */
	public static void logException(Logger logger, Level level, Throwable e) {
		if (logger.isLoggable(level)) {
			logger.log(level, e.getMessage(), e);
		}
	}
}
