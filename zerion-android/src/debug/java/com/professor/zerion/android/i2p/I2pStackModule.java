package com.professor.zerion.android.i2p;

import org.zerionproject.transport.i2p.I2pStack;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;

/** Binds the embedded, bundled I2P stack for debug builds. */
@Module
public abstract class I2pStackModule {

	@Binds
	@Singleton
	abstract I2pStack bindI2pStack(BundledI2pStack stack);
}
