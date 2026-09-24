package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.crypto.CryptoModule;
import org.zerionproject.core.system.ClockModule;
import org.zerionproject.core.test.TestSecureRandomModule;

import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {
		CryptoModule.class,
		ClockModule.class,
		TestSecureRandomModule.class
})
interface ChannelCryptoTestComponent {

	CryptoComponent getCryptoComponent();
}
