package org.zerionproject.core;

import org.zerionproject.core.cleanup.CleanupModule;
import org.zerionproject.core.client.ClientModule;
import org.zerionproject.core.connection.ConnectionModule;
import org.zerionproject.core.contact.ContactModule;
import org.zerionproject.core.crypto.CryptoExecutorModule;
import org.zerionproject.core.crypto.CryptoModule;
import org.zerionproject.core.crypto.pcs.PcsModule;
import org.zerionproject.core.data.DataModule;
import org.zerionproject.core.db.DatabaseExecutorModule;
import org.zerionproject.core.event.EventModule;
import org.zerionproject.core.identity.IdentityModule;
import org.zerionproject.core.io.IoModule;
import org.zerionproject.core.keyagreement.KeyAgreementModule;
import org.zerionproject.core.lifecycle.LifecycleModule;
import org.zerionproject.core.plugin.PluginModule;
import org.zerionproject.core.properties.PropertiesModule;
import org.zerionproject.core.record.RecordModule;
import org.zerionproject.core.reliability.ReliabilityModule;
import org.zerionproject.core.rendezvous.RendezvousModule;
import org.zerionproject.core.settings.SettingsModule;
import org.zerionproject.core.sync.SyncModule;
import org.zerionproject.core.sync.validation.ValidationModule;
import org.zerionproject.core.transport.TransportModule;
import org.zerionproject.core.transport.agreement.TransportKeyAgreementModule;
import org.zerionproject.core.versioning.VersioningModule;

import dagger.Module;

@Module(includes = {
		CleanupModule.class,
		ClientModule.class,
		ConnectionModule.class,
		ContactModule.class,
		CryptoModule.class,
		CryptoExecutorModule.class,
		PcsModule.class,
		DataModule.class,
		DatabaseExecutorModule.class,
		EventModule.class,
		IdentityModule.class,
		IoModule.class,
		KeyAgreementModule.class,
		LifecycleModule.class,
		PluginModule.class,
		PropertiesModule.class,
		RecordModule.class,
		ReliabilityModule.class,
		RendezvousModule.class,
		SettingsModule.class,
		SyncModule.class,
		TransportKeyAgreementModule.class,
		TransportModule.class,
		ValidationModule.class,
		VersioningModule.class
})
public class BrambleCoreModule {
}
