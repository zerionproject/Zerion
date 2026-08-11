package org.zerionproject.core.api.battery.event;

import org.zerionproject.core.api.event.Event;

public class BatteryEvent extends Event {

	private final boolean charging;

	public BatteryEvent(boolean charging) {
		this.charging = charging;
	}

	public boolean isCharging() {
		return charging;
	}
}
