package org.briarproject.bramble.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public class LogUtils {

	public static void logException(Logger logger, Level level, Throwable e) {
		if (logger.isLoggable(level)) {
			logger.log(level, e.getMessage(), e);
		}
	}
}
