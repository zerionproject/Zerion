package org.zerionproject.core;

import android.app.Application;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.tor.AndroidTorWrapper;
import org.zerionproject.tor.TorBinaryVerifier;
import org.zerionproject.transport.TorProcessWatch;

import java.io.File;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

public class ZerionTorWrapper extends AndroidTorWrapper {

	private final TorProcessWatch processWatch;

	public ZerionTorWrapper(Application app,
			AndroidWakeLockManager wakeLockManager, Executor ioExecutor,
			Executor eventExecutor, String architecture, File torDirectory,
			int torSocksPort, int torControlPort,
			@Nullable String controlSocketPath, TorBinaryVerifier verifier,
			TorProcessWatch processWatch) {
		super(app, wakeLockManager, ioExecutor, eventExecutor, architecture,
				torDirectory, torSocksPort, torControlPort, controlSocketPath,
				verifier);
		this.processWatch = processWatch;
	}

	@Override
	public void controlConnectionClosed() {
		boolean wasRunning = isTorRunning();
		super.controlConnectionClosed();
		if (wasRunning) processWatch.controlConnectionLost();
	}
}
