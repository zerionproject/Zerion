package org.zerionproject.core;

import org.zerionproject.core.battery.AndroidBatteryModule;
import org.zerionproject.core.io.DnsModule;
import org.zerionproject.core.network.AndroidNetworkModule;
import org.zerionproject.core.plugin.tor.CircumventionModule;
import org.zerionproject.core.socks.SocksModule;
import org.zerionproject.core.system.AndroidSystemModule;
import org.zerionproject.core.system.AndroidTaskSchedulerModule;
import org.zerionproject.core.system.AndroidWakeLockModule;
import org.zerionproject.core.system.AndroidWakefulIoExecutorModule;
import org.zerionproject.core.system.DefaultThreadFactoryModule;

import dagger.Module;

@Module(includes = {
		AndroidBatteryModule.class,
		AndroidNetworkModule.class,
		AndroidSystemModule.class,
		AndroidTaskSchedulerModule.class,
		AndroidWakefulIoExecutorModule.class,
		AndroidWakeLockModule.class,
		DefaultThreadFactoryModule.class,
		CircumventionModule.class,
		DnsModule.class,
		SocksModule.class
})
public class BrambleAndroidModule {
}
