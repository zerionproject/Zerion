package org.briarproject.briar.sharing;

import dagger.Module;
import dagger.Provides;

@Module
public class SharingModule {

	public static class EagerSingletons {
		// Sharing module now only contains base sharing infrastructure
		// Forum and blog sharing have been removed
	}

	@Provides
	MessageEncoder provideMessageEncoder(MessageEncoderImpl messageEncoder) {
		return messageEncoder;
	}

	@Provides
	SessionEncoder provideSessionEncoder(SessionEncoderImpl sessionEncoder) {
		return sessionEncoder;
	}

	@Provides
	SessionParser provideSessionParser(SessionParserImpl sessionParser) {
		return sessionParser;
	}

}
