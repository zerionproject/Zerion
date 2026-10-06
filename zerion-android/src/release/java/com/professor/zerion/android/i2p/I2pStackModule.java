package com.professor.zerion.android.i2p;

import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pOverlayTransport;
import org.zerionproject.transport.i2p.I2pStack;

import java.util.concurrent.Executor;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

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
