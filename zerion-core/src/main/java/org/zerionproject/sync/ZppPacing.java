package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Supplies the Zerion Pull Protocol's slot cadence. Both values are constant
 * rates: the runner picks one per slot based on whether the connection has
 * carried an application record recently, so within either regime the wire
 * pattern stays fixed-size frames at a constant, jittered cadence.
 */
@NotNullByDefault
public interface ZppPacing {

	long activeIntervalMs();

	long idleIntervalMs();

	/**
	 * How long a connection keeps the active cadence after the last
	 * application record was sent or received.
	 */
	long idleAfterMs();
}
