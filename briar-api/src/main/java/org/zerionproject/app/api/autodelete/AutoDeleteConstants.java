package org.zerionproject.app.api.autodelete;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

public interface AutoDeleteConstants {

	long MIN_AUTO_DELETE_TIMER_MS = MINUTES.toMillis(1);

	long MAX_AUTO_DELETE_TIMER_MS = DAYS.toMillis(365);

	long NO_AUTO_DELETE_TIMER = -1;
}
