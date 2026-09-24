package org.zerionproject.core;

import android.app.Application;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.tor.AndroidTorWrapper;
import org.zerionproject.tor.TorBinaryVerifier;
import org.zerionproject.transport.TorProcessWatch;

import java.io.File;
import java.util.concurrent.Executor;

/**
 * The Android Tor wrapper with the one reaction the library lacks: when the
 * control connection closes while Tor is meant to be running, the tor child
 * has died, and the transport is told so that it can restart Tor instead of
 * dialling a SOCKS port that any local process could now bind.
 */
public class ZerionTorWrapper extends AndroidTorWrapper {

	private final TorProcessWatch processWatch;

	public ZerionTorWrapper(Application app,
			AndroidWakeLockManager wakeLockManager, Executor ioExecutor,
			Executor eventExecutor, String architecture, File torDirectory,
			int torSocksPort, int torControlPort,
			TorBinaryVerifier verifier, TorProcessWatch processWatch) {
		super(app, wakeLockManager, ioExecutor, eventExecutor, architecture,
				torDirectory, torSocksPort, torControlPort, verifier);
		this.processWatch = processWatch;
	}

	@Override
	public void controlConnectionClosed() {
		boolean wasRunning = isTorRunning();
		super.controlConnectionClosed();
		if (wasRunning) processWatch.controlConnectionLost();
	}
}
