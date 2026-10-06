package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.crypto.SettingsStreamCounterStore;
import org.zerionproject.crypto.StreamCounterContactHook;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.sync.ZmmDbRecordSink;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.sync.ZppConnectionRegistry;
import org.zerionproject.sync.ZppConnectionRegistryImpl;
import org.zerionproject.sync.ZppConnectionRunnerImpl;
import org.zerionproject.sync.ZppRecordSink;
import org.zerionproject.wire.ZwfStreamCounter;

import java.util.function.Supplier;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module
public class ZerionTransportModule {


	public static class EagerSingletons {
		@Inject
		ZtpSessionProvider sessionProvider;
	}

	@Provides
	@Singleton
	ZwfStreamCounter provideStreamCounter(SettingsStreamCounterStore store,
			ContactManager contactManager) {
		ZwfStreamCounter counter = new ZwfStreamCounter(store);
		contactManager.registerContactHook(
				new StreamCounterContactHook(counter, store));
		return counter;
	}

	@Provides
	ZwfSessionFactory provideSessionFactory(CryptoComponent crypto,
			Mode3FullRatchet mode3FullRatchet) {
		return new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	@Provides
	Supplier<AuthenticatedCipher> provideCipherFactory() {
		return XSalsa20Poly1305AuthenticatedCipher::new;
	}

	@Provides
	ZtpConnectionEstablisher provideConnectionEstablisher(CryptoComponent crypto,
			PcsRatchet ratchet, Mode3FullRatchet mode3FullRatchet,
			ZwfSessionFactory sessionFactory, ZwfStreamCounter counter,
			Supplier<AuthenticatedCipher> cipherFactory) {
		return new ZtpConnectionEstablisher(crypto, ratchet, mode3FullRatchet,
				sessionFactory, counter, cipherFactory);
	}

	@Provides
	ZppRecordSink provideRecordSink(ZmmDbRecordSink sink) {
		return sink;
	}

	@Provides
	ZppConnectionRegistry provideConnectionRegistry(
			ZppConnectionRegistryImpl registry) {
		return registry;
	}

	@Provides
	@Singleton
	org.zerionproject.sync.ZppPacingPolicy providePacingPolicy() {
		return new org.zerionproject.sync.ZppPacingPolicy();
	}

	@Provides
	@Singleton
	TorProcessWatch provideTorProcessWatch() {
		return new TorProcessWatch();
	}

	@Provides
	ZppConnectionRunner provideConnectionRunner(ZppRecordSink recordSink,
			ZppConnectionRegistry registry,
			org.zerionproject.sync.ZppPacingPolicy pacingPolicy) {
		return new ZppConnectionRunnerImpl(recordSink, registry, pacingPolicy);
	}

	@Provides
	@Singleton
	ZtpSessionProvider provideSessionProvider(LifecycleManager lifecycleManager,
			ZtpSessionProviderImpl provider) {
		lifecycleManager.registerService(provider);
		return provider;
	}

	@Provides
	@Singleton
	ZtpConnectionHandler provideConnectionHandler(
			ZtpConnectionEstablisher establisher, ZtpSessionProvider provider,
			ZppConnectionRunner runner, ConnectionRegistry connectionRegistry,
			org.zerionproject.core.api.plugin.OnionClientAuthManager
					onionClientAuthManager,
			RootEvolutionManager rootEvolutionManager) {
		return new ZtpConnectionHandlerImpl(establisher, provider, runner,
				connectionRegistry, onionClientAuthManager,
				rootEvolutionManager);
	}
}
