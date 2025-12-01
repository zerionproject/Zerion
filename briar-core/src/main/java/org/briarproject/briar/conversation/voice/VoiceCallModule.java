package org.briarproject.briar.conversation.voice;

import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Dagger module for voice call components.
 */
@Module
public class VoiceCallModule {

	@Provides
	@Singleton
	VoiceCallCrypto provideVoiceCallCrypto(VoiceCallCryptoImpl voiceCallCrypto) {
		return voiceCallCrypto;
	}

	@Provides
	@Singleton
	VoiceCallConnectionManager provideVoiceCallConnectionManager(
			VoiceCallConnectionManagerImpl manager) {
		return manager;
	}
}
