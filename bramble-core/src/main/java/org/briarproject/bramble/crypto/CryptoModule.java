package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyAgreementCrypto;
import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.bramble.api.crypto.StreamDecrypterFactory;
import org.briarproject.bramble.api.crypto.StreamEncrypterFactory;
import org.briarproject.bramble.api.crypto.TransportCrypto;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.system.SecureRandomProvider;

import java.security.SecureRandom;

import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class CryptoModule {

	@Provides
	AuthenticatedCipher provideAuthenticatedCipher() {
		return new XSalsa20Poly1305AuthenticatedCipher();
	}

	@Provides
	@Singleton
	CryptoComponent provideCryptoComponent(
			SecureRandomProvider secureRandomProvider,
			ScryptKdf passwordBasedKdf) {
		return new CryptoComponentImpl(secureRandomProvider, passwordBasedKdf);
	}

	@Provides
	PasswordStrengthEstimator providePasswordStrengthEstimator() {
		return new PasswordStrengthEstimatorImpl();
	}

	@Provides
	TransportCrypto provideTransportCrypto(
			TransportCryptoImpl transportCrypto) {
		return transportCrypto;
	}

	@Provides
	StreamDecrypterFactory provideStreamDecrypterFactory(
			Provider<AuthenticatedCipher> cipherProvider,
			PcsRatchet pcsRatchet, SkippedKeyStore skippedKeyStore) {
		return new StreamDecrypterFactoryImpl(cipherProvider, pcsRatchet,
				skippedKeyStore);
	}

	@Provides
	StreamEncrypterFactory provideStreamEncrypterFactory(
			CryptoComponent crypto, TransportCrypto transportCrypto,
			Provider<AuthenticatedCipher> cipherProvider, PcsRatchet pcsRatchet,
			PqRatchet pqRatchet) {
		return new StreamEncrypterFactoryImpl(crypto, transportCrypto,
				cipherProvider, pcsRatchet, pqRatchet);
	}

	@Provides
	KeyAgreementCrypto provideKeyAgreementCrypto(
			KeyAgreementCryptoImpl keyAgreementCrypto) {
		return keyAgreementCrypto;
	}

	@Provides
	SecureRandom getSecureRandom(CryptoComponent crypto) {
		return crypto.getSecureRandom();
	}

}
