package org.briarproject.bramble.plugin.bluetooth;

import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.sync.event.CloseSyncConnectionsEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.LinkedList;
import java.util.List;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import static org.briarproject.bramble.api.plugin.BluetoothConstants.ID;

@NotNullByDefault
@ThreadSafe
class BluetoothConnectionLimiterImpl implements BluetoothConnectionLimiter {
	private final EventBus eventBus;

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final List<DuplexTransportConnection> connections =
			new LinkedList<>();
	@GuardedBy("lock")
	private int limitingInProgress = 0;

	BluetoothConnectionLimiterImpl(EventBus eventBus) {
		this.eventBus = eventBus;
	}

	@Override
	public void startLimiting() {
		synchronized (lock) {
			limitingInProgress++;
		}
		eventBus.broadcast(new CloseSyncConnectionsEvent(ID));
	}

	@Override
	public void endLimiting() {
		synchronized (lock) {
			limitingInProgress--;
			if (limitingInProgress < 0) {
				throw new IllegalStateException();
			}
		}
	}

	@Override
	public boolean canOpenContactConnection() {
		synchronized (lock) {
			if (limitingInProgress > 0) {
				return false;
			} else {
				return true;
			}
		}
	}

	@Override
	public void connectionOpened(DuplexTransportConnection conn) {
		synchronized (lock) {
			connections.add(conn);
		}
	}

	@Override
	public void connectionClosed(DuplexTransportConnection conn) {
		synchronized (lock) {
			connections.remove(conn);
		}
	}

	@Override
	public void allConnectionsClosed() {
		synchronized (lock) {
			connections.clear();
		}
	}
}
