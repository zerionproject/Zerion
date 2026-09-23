package com.professor.zerion.android.i2p;

import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pOverlayTransport;
import org.zerionproject.transport.i2p.I2pStack;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Release builds ship no I2P router. The plugin factory that depends on the
 * stack is never registered in a release build, so this binding exists only
 * to satisfy the object graph and refuses to create a transport.
 */
@Module
public class I2pStackModule {

	@Provides
	@Singleton
	I2pStack provideI2pStack() {
		return (Executor ioExecutor, ZtpConnectionHandler handler) -> {
			throw new UnsupportedOperationException(
					"I2P is not available in release builds");
		};
	}
}
