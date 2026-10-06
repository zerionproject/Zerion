package org.zerionproject.core;

import android.app.Application;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.tor.TorBinaryPins;
import org.zerionproject.tor.TorBinaryVerifier;
import org.zerionproject.tor.TorWrapper;
import org.zerionproject.core.api.event.EventExecutor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.TorControlPort;
import org.zerionproject.core.api.plugin.TorDirectory;
import org.zerionproject.core.api.plugin.OnionTargetFactory;
import org.zerionproject.core.api.plugin.TorSocksPath;
import org.zerionproject.core.api.plugin.TorSocksPort;
import org.zerionproject.core.socks.LocalSockets;
import org.zerionproject.core.socks.UnixOnionTargetFactory;
import org.zerionproject.transport.TorControlSocketFactory;

import java.io.File;
import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.util.AndroidUtils.getSupportedArchitectures;

@Module
public class ZerionTorWrapperModule {

	@Provides
	@Singleton
	TorWrapper provideZerionTorWrapper(Application app,
			AndroidWakeLockManager wakeLockManager,
			@IoExecutor Executor ioExecutor,
			@EventExecutor Executor eventExecutor,
			@TorDirectory File torDirectory, @TorSocksPort int torSocksPort,
			@TorControlPort int torControlPort,
			@TorSocksPath File socksPath,
			org.zerionproject.transport.TorProcessWatch processWatch) {
		return new ZerionTorWrapper(app, wakeLockManager, ioExecutor,
				eventExecutor, architecture(), torDirectory, torSocksPort,
				torControlPort, controlSocketPath(socksPath.getAbsolutePath()),
				shippedPins(), processWatch);
	}

	static String controlSocketPath(String socksPath) {
		return socksPath + "c";
	}

	@Provides
	@Singleton
	TorControlSocketFactory provideTorControlSocketFactory(
			@TorSocksPath File socksPath) {
		String path = controlSocketPath(socksPath.getAbsolutePath());
		return readTimeoutMs -> LocalSockets.connect(path, readTimeoutMs);
	}

	@Provides
	@Singleton
	OnionTargetFactory provideOnionTargetFactory(Application app) {
		return new UnixOnionTargetFactory(new File(app.getFilesDir(), "zo"));
	}

	private static TorBinaryVerifier shippedPins() {
		return (tor, lyrebird) -> TorBinaryPins.shipped().verify(tor, lyrebird);
	}

	@Provides
	@Singleton
	org.zerionproject.core.socks.TorSocksConnector provideTorSocksConnector(
			@TorSocksPath File socksPath) {
		return new org.zerionproject.core.socks.UnixTorSocksConnector(
				socksPath);
	}

	private static String architecture() {
		for (String abi : getSupportedArchitectures()) {
			if (abi.startsWith("x86_64")) return "x86_64_pie";
			else if (abi.startsWith("x86")) return "x86_pie";
			else if (abi.startsWith("arm64")) return "arm64_pie";
			else if (abi.startsWith("armeabi")) return "arm_pie";
		}
		throw new UnsupportedOperationException(
				"No supported Tor binary for device architecture");
	}
}
