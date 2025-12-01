package com.professor.zerion.android;

import android.net.TrafficStats;
import android.os.Process;

import org.briarproject.bramble.api.lifecycle.Service;
import com.professor.zerion.android.api.NetworkUsageMetrics;
import org.briarproject.nullsafety.NotNullByDefault;


import static org.briarproject.bramble.util.LogUtils.now;

@NotNullByDefault
class NetworkUsageMetricsImpl implements NetworkUsageMetrics, Service {


	private volatile long startTime, rxBytes, txBytes;

	@Override
	public void startService() {
		startTime = now();
		int uid = Process.myUid();
		rxBytes = TrafficStats.getUidRxBytes(uid);
		txBytes = TrafficStats.getUidTxBytes(uid);
	}

	@Override
	public void stopService() {
	}

	@Override
	public Metrics getMetrics() {
		long sessionDurationMs = now() - startTime;
		int uid = Process.myUid();
		long rx = TrafficStats.getUidRxBytes(uid) - rxBytes;
		long tx = TrafficStats.getUidTxBytes(uid) - txBytes;
		return new Metrics(sessionDurationMs, rx, tx);
	}
}
