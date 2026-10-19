package org.zerionproject.core.plugin.tor.auth;

import dagger.BindsOptionalOf;
import dagger.Module;

@Module
public interface OnionAuthHooksModule {

	@BindsOptionalOf
	OnionAuthDebugHooks optionalDebugHooks();
}
