package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.keyagreement.KeyAgreementTask;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.zerionproject.core.api.keyagreement.PayloadParser;

import dagger.Module;
import dagger.Provides;

@Module
public class KeyAgreementModule {

	@Provides
	KeyAgreementTask provideKeyAgreementTask(
			KeyAgreementTaskImpl keyAgreementTask) {
		return keyAgreementTask;
	}

	@Provides
	PayloadEncoder providePayloadEncoder(PayloadEncoderImpl payloadEncoder) {
		return payloadEncoder;
	}

	@Provides
	PayloadParser providePayloadParser(PayloadParserImpl payloadParser) {
		return payloadParser;
	}

	@Provides
	ConnectionChooser provideConnectionChooser(
			ConnectionChooserImpl connectionChooser) {
		return connectionChooser;
	}
}
