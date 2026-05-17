package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyAgreementCrypto;
import org.briarproject.bramble.api.crypto.PasswordStrengthEstimator;
import org.briarproject.bramble.api.crypto.StreamDecrypterFactory;
import org.briarproject.bramble.api.crypto.StreamEncrypterFactory;
import org.briarproject.bramble.api.crypto.TransportCrypto;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.system.SecureRandomProvider;
import org.briarproject.bramble.crypto.pcs.PcsStateManager;

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
			ScryptKdf scryptKdf,
			Argon2idKdf argon2idKdf) {
		return new CryptoComponentImpl(secureRandomProvider, scryptKdf,
				argon2idKdf);
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
			PcsRatchet pcsRatchet, PqRatchet pqRatchet,
			SkippedKeyStore skippedKeyStore,
			PcsStateManager pcsStateManager,
			Mode3FullRatchet mode3FullRatchet) {
		return new StreamDecrypterFactoryImpl(cipherProvider, pcsRatchet,
				pqRatchet, skippedKeyStore, pcsStateManager, mode3FullRatchet);
	}

	@Provides
	StreamEncrypterFactory provideStreamEncrypterFactory(
			CryptoComponent crypto, TransportCrypto transportCrypto,
			Provider<AuthenticatedCipher> cipherProvider, PcsRatchet pcsRatchet,
			PqRatchet pqRatchet, PcsStateManager pcsStateManager,
			Mode3FullRatchet mode3FullRatchet) {
		return new StreamEncrypterFactoryImpl(crypto, transportCrypto,
				cipherProvider, pcsRatchet, pqRatchet, pcsStateManager,
				mode3FullRatchet);
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
