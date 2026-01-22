package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for Post-Compromise Security (PCS) components.
 * <p>
 * This module provides the symmetric ratchet implementation (Mode 1)
 * and the skipped key store. For production use, the SkippedKeyStore
 * should be replaced with a database-backed implementation.
 */
@Module
public class PcsModule {

	@Provides
	PcsRatchet providePcsRatchet(PcsRatchetImpl pcsRatchet) {
		return pcsRatchet;
	}

	@Provides
	@Singleton
	SkippedKeyStore provideSkippedKeyStore(DatabaseSkippedKeyStore store) {
		return store;
	}

	@Provides
	PcsHeaderCodec providePcsHeaderCodec() {
		return new PcsHeaderCodec();
	}
}
