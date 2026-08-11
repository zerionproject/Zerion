package org.zerionproject.core;

import org.zerionproject.core.account.AccountModule;
import org.zerionproject.core.cleanup.CleanupModule;
import org.zerionproject.core.contact.ContactModule;
import org.zerionproject.core.crypto.CryptoExecutorModule;
import org.zerionproject.core.crypto.pcs.PcsModule;
import org.zerionproject.core.db.DatabaseExecutorModule;
import org.zerionproject.core.identity.IdentityModule;
import org.zerionproject.core.lifecycle.LifecycleModule;
import org.zerionproject.core.plugin.PluginModule;
import org.zerionproject.core.properties.PropertiesModule;
import org.zerionproject.core.rendezvous.RendezvousModule;
import org.zerionproject.core.sync.validation.ValidationModule;
import org.zerionproject.core.transport.TransportModule;
import org.zerionproject.core.transport.agreement.TransportKeyAgreementModule;
import org.zerionproject.core.versioning.VersioningModule;

public interface BrambleCoreEagerSingletons {

	void inject(AccountModule.EagerSingletons init);

	void inject(CleanupModule.EagerSingletons init);

	void inject(ContactModule.EagerSingletons init);

	void inject(CryptoExecutorModule.EagerSingletons init);

	void inject(DatabaseExecutorModule.EagerSingletons init);

	void inject(IdentityModule.EagerSingletons init);

	void inject(LifecycleModule.EagerSingletons init);

	void inject(PcsModule.EagerSingletons init);

	void inject(PluginModule.EagerSingletons init);

	void inject(PropertiesModule.EagerSingletons init);

	void inject(RendezvousModule.EagerSingletons init);

	void inject(TransportKeyAgreementModule.EagerSingletons init);

	void inject(TransportModule.EagerSingletons init);

	void inject(ValidationModule.EagerSingletons init);

	void inject(VersioningModule.EagerSingletons init);

	class Helper {

		public static void injectEagerSingletons(BrambleCoreEagerSingletons c) {
			c.inject(new AccountModule.EagerSingletons());
			c.inject(new CleanupModule.EagerSingletons());
			c.inject(new ContactModule.EagerSingletons());
			c.inject(new CryptoExecutorModule.EagerSingletons());
			c.inject(new DatabaseExecutorModule.EagerSingletons());
			c.inject(new IdentityModule.EagerSingletons());
			c.inject(new LifecycleModule.EagerSingletons());
			c.inject(new PcsModule.EagerSingletons());
			c.inject(new RendezvousModule.EagerSingletons());
			c.inject(new PluginModule.EagerSingletons());
			c.inject(new PropertiesModule.EagerSingletons());
			c.inject(new TransportKeyAgreementModule.EagerSingletons());
			c.inject(new TransportModule.EagerSingletons());
			c.inject(new ValidationModule.EagerSingletons());
			c.inject(new VersioningModule.EagerSingletons());
		}
	}
}
