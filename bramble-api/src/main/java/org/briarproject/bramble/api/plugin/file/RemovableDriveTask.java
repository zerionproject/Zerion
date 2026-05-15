package org.briarproject.bramble.api.plugin.file;

import org.briarproject.bramble.api.Consumer;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface RemovableDriveTask extends Runnable {

	TransportProperties getTransportProperties();

	void addObserver(Consumer<State> observer);

	void removeObserver(Consumer<State> observer);

	class State {

		private final long done, total;
		private final boolean finished, success;

		public State(long done, long total, boolean finished, boolean success) {
			this.done = done;
			this.total = total;
			this.finished = finished;
			this.success = success;
		}

		public long getDone() {
			return done;
		}

		public long getTotal() {
			return total;
		}

		public boolean isFinished() {
			return finished;
		}

		public boolean isSuccess() {
			return success;
		}
	}
}
