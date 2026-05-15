package org.briarproject.bramble.io;

import dagger.Module;
import dagger.Provides;
import okhttp3.Dns;

@Module
public class DnsModule {

	@Provides
	Dns provideDns(NoDns noDns) {
		return noDns;
	}

}
