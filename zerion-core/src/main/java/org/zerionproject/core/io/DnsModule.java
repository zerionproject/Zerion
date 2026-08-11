package org.zerionproject.core.io;

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
