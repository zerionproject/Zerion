package org.zerionproject.app.conversation.voice;

import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

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
