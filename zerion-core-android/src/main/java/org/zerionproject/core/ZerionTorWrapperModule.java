package org.zerionproject.core;

import android.app.Application;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.briarproject.onionwrapper.AndroidTorWrapper;
import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.event.EventExecutor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.TorControlPort;
import org.zerionproject.core.api.plugin.TorDirectory;
import org.zerionproject.core.api.plugin.TorSocksPort;

import java.io.File;
import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.zerionproject.core.util.AndroidUtils.getSupportedArchitectures;

/**
 * Provides the Android Tor wrapper for the native transport. It lives in the
 * Android Bramble module because that is where the {@code AndroidTorWrapper}
 * implementation is on the classpath; the rest of the native transport wiring
 * consumes only the {@link TorWrapper} interface.
 */
@Module
public class ZerionTorWrapperModule {

	@Provides
	@Singleton
	TorWrapper provideZerionTorWrapper(Application app,
			AndroidWakeLockManager wakeLockManager,
			@IoExecutor Executor ioExecutor,
			@EventExecutor Executor eventExecutor,
			@TorDirectory File torDirectory, @TorSocksPort int torSocksPort,
			@TorControlPort int torControlPort) {
		return new AndroidTorWrapper(app, wakeLockManager, ioExecutor,
				eventExecutor, architecture(), torDirectory, torSocksPort,
				torControlPort);
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
