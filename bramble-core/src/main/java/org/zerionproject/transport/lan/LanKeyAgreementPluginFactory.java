package org.zerionproject.transport.lan;

import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

/**
 * Registers the key-agreement-only LAN transport so the pairing key-agreement
 * subsystem has a transport to run over, enabling offline contact pairing.
 */
@Immutable
@NotNullByDefault
public class LanKeyAgreementPluginFactory implements DuplexPluginFactory {

	@Inject
	public LanKeyAgreementPluginFactory() {
	}

	@Override
	public TransportId getId() {
		return LanTcpConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return LanKeyAgreementPlugin.MAX_LATENCY;
	}

	@Override
	public DuplexPlugin createPlugin(PluginCallback callback) {
		return new LanKeyAgreementPlugin(callback);
	}
}
