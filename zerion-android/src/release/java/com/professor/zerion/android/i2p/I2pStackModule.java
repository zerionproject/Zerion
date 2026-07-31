package com.professor.zerion.android.i2p;

import org.zerionproject.transport.i2p.I2pStack;
import org.zerionproject.transport.i2p.SamI2pStack;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;

/**
 * Binds the SAM-based I2P stack for release builds. The I2P plugin is disabled
 * by default in release, so this binding keeps the graph complete without
 * bundling a router into the shipped, Tor-only build.
 */
@Module
public abstract class I2pStackModule {

	@Binds
	@Singleton
	abstract I2pStack bindI2pStack(SamI2pStack stack);
}
