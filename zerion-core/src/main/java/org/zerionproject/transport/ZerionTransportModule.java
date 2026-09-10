package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.crypto.SettingsStreamCounterStore;
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

/**
 * Wires the native Zerion transport and pull-protocol into the app. This is the
 * core-side half of the plugin/connection/sync transport path; the
 * Android-specific half (the Tor wrapper and the poller) lives in an Android
 * module.
 */
@Module
public class ZerionTransportModule {


	public static class EagerSingletons {
		@Inject
		ZtpSessionProvider sessionProvider;
	}

	/**
	 * The stream counter MUST be a process-wide singleton: two instances for the
	 * same contact would hand out the same outgoing stream id and reuse a
	 * (key, nonce) pair. This is the enforcement point for that invariant.
	 */
	@Provides
	@Singleton
	ZwfStreamCounter provideStreamCounter(SettingsStreamCounterStore store) {
		return new ZwfStreamCounter(store);
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
			HandshakeCrypto handshakeCrypto, PcsRatchet ratchet,
			Mode3FullRatchet mode3FullRatchet, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter,
			Supplier<AuthenticatedCipher> cipherFactory) {
		return new ZtpConnectionEstablisher(crypto, handshakeCrypto, ratchet,
				mode3FullRatchet, sessionFactory, counter, cipherFactory);
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
	org.zerionproject.sync.ZppPacingPolicy providePacingPolicy(
			org.zerionproject.core.api.network.NetworkManager networkManager,
			org.zerionproject.core.api.settings.SettingsManager settingsManager,
			@org.zerionproject.core.api.db.DatabaseExecutor
					java.util.concurrent.Executor dbExecutor,
			org.zerionproject.core.api.event.EventBus eventBus) {
		org.zerionproject.sync.ZppPacingPolicy policy =
				new org.zerionproject.sync.ZppPacingPolicy(networkManager,
						settingsManager, dbExecutor);
		eventBus.addListener(policy);
		return policy;
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
			ZppConnectionRunner runner, ConnectionRegistry connectionRegistry) {
		return new ZtpConnectionHandlerImpl(establisher, provider, runner,
				connectionRegistry);
	}
}
