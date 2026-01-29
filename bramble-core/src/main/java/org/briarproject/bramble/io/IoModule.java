package org.briarproject.bramble.io;

import org.briarproject.bramble.api.WeakSingletonProvider;
import org.briarproject.bramble.api.io.TimeoutMonitor;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import javax.net.SocketFactory;

import dagger.Module;
import dagger.Provides;
import okhttp3.Dns;
import okhttp3.OkHttpClient;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Module
public class IoModule {

	private static final int CONNECT_TIMEOUT = 60_000;

	@Provides
	@Singleton
	TimeoutMonitor provideTimeoutMonitor(TimeoutMonitorImpl timeoutMonitor) {
		return timeoutMonitor;
	}
	@Provides
	@Singleton
	WeakSingletonProvider<OkHttpClient> provideOkHttpClientProvider(
			SocketFactory torSocketFactory, Dns noDnsLookups) {
		return new WeakSingletonProvider<OkHttpClient>() {
			@Override
			@Nonnull
			public OkHttpClient createInstance() {
				return new OkHttpClient.Builder()
						.socketFactory(torSocketFactory)
						.dns(noDnsLookups)
						.connectTimeout(CONNECT_TIMEOUT, MILLISECONDS)
						.build();
			}
		};
	}
}
