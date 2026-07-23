package org.zerionproject.core.api.plugin.duplex;

import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface DuplexPlugin extends Plugin {

	@Wakeful
	@Nullable
	DuplexTransportConnection createConnection(TransportProperties p);

	boolean supportsKeyAgreement();

	@Nullable
	KeyAgreementListener createKeyAgreementListener(byte[] localCommitment);

	@Nullable
	DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor);

	boolean supportsRendezvous();

	@Nullable
	RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming);
}
